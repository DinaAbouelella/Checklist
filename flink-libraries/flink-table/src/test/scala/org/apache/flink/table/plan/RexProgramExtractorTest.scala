/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.plan

import java.math.BigDecimal

import org.apache.calcite.rex.{RexBuilder, RexProgram, RexProgramBuilder}
import org.apache.calcite.sql.SqlPostfixOperator
import org.apache.calcite.sql.`type`.SqlTypeName.{BIGINT, INTEGER, VARCHAR}
import org.apache.calcite.sql.fun.SqlStdOperatorTable
import org.apache.flink.table.expressions._
import org.apache.flink.table.plan.util.RexProgramExtractor
import org.apache.flink.table.utils.InputTypeBuilder.inputOf
import org.apache.flink.table.validate.FunctionCatalog
import org.hamcrest.CoreMatchers.is
import org.junit.Assert.{assertArrayEquals, assertEquals, assertThat}
import org.junit.Test

import scala.collection.JavaConverters._

class RexProgramExtractorTest extends RexProgramTestBase {

  private val functionCatalog: FunctionCatalog = FunctionCatalog.withBuiltIns

  @Test
  def testExtractRefInputFields(): Unit = {
    val usedFields = RexProgramExtractor.extractRefInputFields(buildSimpleRexProgram())
    assertArrayEquals(usedFields, Array(2, 3, 1))
  }

  @Test
  def testExtractSimpleCondition(): Unit = {
    val builder: RexBuilder = new RexBuilder(typeFactory)
    val program = buildSimpleRexProgram()

    val firstExp = ExpressionParser.parseExpression("id > 6")
    val secondExp = ExpressionParser.parseExpression("amount * price < 100")
    val expected: Array[Expression] = Array(firstExp, secondExp)

    val (convertedExpressions, unconvertedRexNodes) =
      RexProgramExtractor.extractConjunctiveConditions(
        program,
        builder,
        functionCatalog)

    assertExpressionArrayEquals(expected, convertedExpressions)
    assertEquals(0, unconvertedRexNodes.length)
  }

  @Test
  def testExtractSingleCondition(): Unit = {
    val inputRowType = typeFactory.createStructType(allFieldTypes, allFieldNames)
    val builder = new RexProgramBuilder(inputRowType, rexBuilder)

    // amount
    val t0 = rexBuilder.makeInputRef(allFieldTypes.get(2), 2)
    // id
    val t1 = rexBuilder.makeInputRef(allFieldTypes.get(1), 1)

    // a = amount >= id
    val a = builder.addExpr(rexBuilder.makeCall(SqlStdOperatorTable.GREATER_THAN_OR_EQUAL, t0, t1))
    builder.addCondition(a)

    val program = builder.getProgram
    val relBuilder: RexBuilder = new RexBuilder(typeFactory)
    val (convertedExpressions, unconvertedRexNodes) =
      RexProgramExtractor.extractConjunctiveConditions(
        program,
        relBuilder,
        functionCatalog)

    val expected: Array[Expression] = Array(ExpressionParser.parseExpression("amount >= id"))
    assertExpressionArrayEquals(expected, convertedExpressions)
    assertEquals(0, unconvertedRexNodes.length)
  }

  // ((a AND b) OR c) AND (NOT d) => (a OR c) AND (b OR c) AND (NOT d)
  @Test
  def testExtractCnfCondition(): Unit = {
    val inputRowType = typeFactory.createStructType(allFieldTypes, allFieldNames)
    val builder = new RexProgramBuilder(inputRowType, rexBuilder)

    // amount
    val t0 = rexBuilder.makeInputRef(allFieldTypes.get(2), 2)
    // id
    val t1 = rexBuilder.makeInputRef(allFieldTypes.get(1), 1)
    // price
    val t2 = rexBuilder.makeInputRef(allFieldTypes.get(3), 3)
    // 100
    val t3 = rexBuilder.makeExactLiteral(BigDecimal.valueOf(100L))

    // a = amount < 100
    val a = builder.addExpr(rexBuilder.makeCall(SqlStdOperatorTable.LESS_THAN, t0, t3))
    // b = id > 100
    val b = builder.addExpr(rexBuilder.makeCall(SqlStdOperatorTable.GREATER_THAN, t1, t3))
    // c = price == 100
    val c = builder.addExpr(rexBuilder.makeCall(SqlStdOperatorTable.EQUALS, t2, t3))
    // d = amount <= id
    val d = builder.addExpr(rexBuilder.makeCall(SqlStdOperatorTable.LESS_THAN_OR_EQUAL, t0, t1))

    // a AND b
    val and = builder.addExpr(rexBuilder.makeCall(SqlStdOperatorTable.AND, List(a, b).asJava))
    // (a AND b) or c
    val or = builder.addExpr(rexBuilder.makeCall(SqlStdOperatorTable.OR, List(and, c).asJava))
    // not d
    val not = builder.addExpr(rexBuilder.makeCall(SqlStdOperatorTable.NOT, List(d).asJava))

    // (a AND b) OR c) AND (NOT d)
    builder.addCondition(builder.addExpr(
      rexBuilder.makeCall(SqlStdOperatorTable.AND, List(or, not).asJava)))

    val program = builder.getProgram
    val relBuilder: RexBuilder = new RexBuilder(typeFactory)
    val (convertedExpressions, unconvertedRexNodes) =
      RexProgramExtractor.extractConjunctiveConditions(
        program,
        relBuilder,
        functionCatalog)

    val expected: Array[Expression] = Array(
      ExpressionParser.parseExpression("amount < 100 || price == 100"),
      ExpressionParser.parseExpression("id > 100 || price == 100"),
      ExpressionParser.parseExpression("!(amount <= id)"))
    assertExpressionArrayEquals(expected, convertedExpressions)
    assertEquals(0, unconvertedRexNodes.length)
  }

  @Test
  def testExtractArithmeticConditions(): Unit = {
    val inputRowType = typeFactory.createStructType(allFieldTypes, allFieldNames)
    val builder = new RexProgramBuilder(inputRowType, rexBuilder)

    // amount
    val t0 = rexBuilder.makeInputRef(allFieldTypes.get(2), 2)
    // id
    val t1 = rexBuilder.makeInputRef(allFieldTypes.get(1), 1)
    // 100
    val t2 = rexBuilder.makeExactLiteral(BigDecimal.valueOf(100L))

    val condition = List(
      // amount < id
      builder.addExpr(rexBuilder.makeCall(SqlStdOperatorTable.LESS_THAN, t0, t1)),
      // amount <= id
      builder.addExpr(rexBuilder.makeCall(SqlStdOperatorTable.LESS_THAN_OR_EQUAL, t0, t1)),
      // amount <> id
      builder.addExpr(rexBuilder.makeCall(SqlStdOperatorTable.NOT_EQUALS, t0, t1)),
      // amount == id
      builder.addExpr(rexBuilder.makeCall(SqlStdOperatorTable.EQUALS, t0, t1)),
      // amount >= id
      builder.addExpr(rexBuilder.makeCall(SqlStdOperatorTable.GREATER_THAN_OR_EQUAL, t0, t1)),
      // amount > id
      builder.addExpr(rexBuilder.makeCall(SqlStdOperatorTable.GREATER_THAN, t0, t1)),
      // amount + id == 100
      builder.addExpr(rexBuilder.makeCall(SqlStdOperatorTable.EQUALS,
        rexBuilder.makeCall(SqlStdOperatorTable.PLUS, t0, t1), t2)),
      // amount - id == 100
      builder.addExpr(rexBuilder.makeCall(SqlStdOperatorTable.EQUALS,
        rexBuilder.makeCall(SqlStdOperatorTable.MINUS, t0, t1), t2)),
      // amount * id == 100
      builder.addExpr(rexBuilder.makeCall(SqlStdOperatorTable.EQUALS,
        rexBuilder.makeCall(SqlStdOperatorTable.MULTIPLY, t0, t1), t2)),
      // amount / id == 100
      builder.addExpr(rexBuilder.makeCall(SqlStdOperatorTable.EQUALS,
        rexBuilder.makeCall(SqlStdOperatorTable.DIVIDE, t0, t1), t2)),
      // -amount == 100
      builder.addExpr(rexBuilder.makeCall(SqlStdOperatorTable.EQUALS,
        rexBuilder.makeCall(SqlStdOperatorTable.UNARY_MINUS, t0), t2))
    ).asJava

    builder.addCondition(builder.addExpr(rexBuilder.makeCall(SqlStdOperatorTable.AND, condition)))
    val program = builder.getProgram
    val relBuilder: RexBuilder = new RexBuilder(typeFactory)
    val (convertedExpressions, unconvertedRexNodes) =
      RexProgramExtractor.extractConjunctiveConditions(
        program,
        relBuilder,
        functionCatalog)

    val expected: Array[Expression] = Array(
      ExpressionParser.parseExpression("amount < id"),
      ExpressionParser.parseExpression("amount <= id"),
      ExpressionParser.parseExpression("amount <> id"),
      ExpressionParser.parseExpression("amount == id"),
      ExpressionParser.parseExpression("amount >= id"),
      ExpressionParser.parseExpression("amount > id"),
      ExpressionParser.parseExpression("amount + id == 100"),
      ExpressionParser.parseExpression("amount - id == 100"),
      ExpressionParser.parseExpression("amount * id == 100"),
      ExpressionParser.parseExpression("amount / id == 100"),
      ExpressionParser.parseExpression("-amount == 100")
    )
    assertExpressionArrayEquals(expected, convertedExpressions)
    assertEquals(0, unconvertedRexNodes.length)
  }

  @Test
  def testExtractPostfixConditions(): Unit = {
    testExtractSinglePostfixCondition(4, SqlStdOperatorTable.IS_NULL, "('flag).isNull")
    // IS_NOT_NULL will be eliminated since flag is not nullable
    // testExtractSinglePostfixCondition(SqlStdOperatorTable.IS_NOT_NULL, "('flag).isNotNull")
    testExtractSinglePostfixCondition(4, SqlStdOperatorTable.IS_TRUE, "('flag).isTrue")
    testExtractSinglePostfixCondition(4, SqlStdOperatorTable.IS_NOT_TRUE, "('flag).isNotTrue")
    testExtractSinglePostfixCondition(4, SqlStdOperatorTable.IS_FALSE, "('flag).isFalse")
    testExtractSinglePostfixCondition(4, SqlStdOperatorTable.IS_NOT_FALSE, "('flag).isNotFalse")
  }

  @Test
  def testExtractConditionWithFunctionCalls(): Unit = {
    val inputRowType = typeFactory.createStructType(allFieldTypes, allFieldNames)
    val builder = new RexProgramBuilder(inputRowType, rexBuilder)

    // amount
    val t0 = rexBuilder.makeInputRef(allFieldTypes.get(2), 2)
    // id
    val t1 = rexBuilder.makeInputRef(allFieldTypes.get(1), 1)
    // 100
    val t2 = rexBuilder.makeExactLiteral(BigDecimal.valueOf(100L))

    // sum(amount) > 100
    val condition1 = builder.addExpr(
      rexBuilder.makeCall(SqlStdOperatorTable.GREATER_THAN,
        rexBuilder.makeCall(SqlStdOperatorTable.SUM, t0), t2))

    // min(id) == 100
    val condition2 = builder.addExpr(
      rexBuilder.makeCall(SqlStdOperatorTable.EQUALS,
        rexBuilder.makeCall(SqlStdOperatorTable.MIN, t1), t2))

    builder.addCondition(builder.addExpr(
      rexBuilder.makeCall(SqlStdOperatorTable.AND, condition1, condition2)))

    val program = builder.getProgram
    val relBuilder: RexBuilder = new RexBuilder(typeFactory)
    val (convertedExpressions, unconvertedRexNodes) =
      RexProgramExtractor.extractConjunctiveConditions(
        program,
        relBuilder,
        functionCatalog)

    val expected: Array[Expression] = Array(
      GreaterThan(Sum(UnresolvedFieldReference("amount")), Literal(100)),
      EqualTo(Min(UnresolvedFieldReference("id")), Literal(100))
    )
    assertExpressionArrayEquals(expected, convertedExpressions)
    assertEquals(0, unconvertedRexNodes.length)
  }

  @Test
  def testExtractWithUnsupportedConditions(): Unit = {
    val inputRowType = typeFactory.createStructType(allFieldTypes, allFieldNames)
    val builder = new RexProgramBuilder(inputRowType, rexBuilder)

    // amount
    val t0 = rexBuilder.makeInputRef(allFieldTypes.get(2), 2)
    // id
    val t1 = rexBuilder.makeInputRef(allFieldTypes.get(1), 1)
    // 100
    val t2 = rexBuilder.makeExactLiteral(BigDecimal.valueOf(100L))

    // unsupported now: amount.cast(BigInteger)
    val cast = builder.addExpr(rexBuilder.makeCast(allFieldTypes.get(1), t0))

    // unsupported now: amount.cast(BigInteger) > 100
    val condition1 = builder.addExpr(
      rexBuilder.makeCall(SqlStdOperatorTable.GREATER_THAN, cast, t2))

    // amount <= id
    val condition2 = builder.addExpr(
      rexBuilder.makeCall(SqlStdOperatorTable.LESS_THAN_OR_EQUAL, t0, t1))

    // contains unsupported condition: (amount.cast(BigInteger) > 100 OR amount <= id)
    val condition3 = builder.addExpr(
      rexBuilder.makeCall(SqlStdOperatorTable.OR, condition1, condition2))

    // only condition2 can be translated
    builder.addCondition(
      rexBuilder.makeCall(SqlStdOperatorTable.AND, condition1, condition2, condition3))

    val program = builder.getProgram
    val relBuilder: RexBuilder = new RexBuilder(typeFactory)
    val (convertedExpressions, unconvertedRexNodes) =
      RexProgramExtractor.extractConjunctiveConditions(
        program,
        relBuilder,
        functionCatalog)

    val expected: Array[Expression] = Array(
      ExpressionParser.parseExpression("amount <= id")
    )
    assertExpressionArrayEquals(expected, convertedExpressions)
    assertEquals(2, unconvertedRexNodes.length)
    assertEquals(">(CAST($2):BIGINT NOT NULL, 100)", unconvertedRexNodes(0).toString)
    assertEquals("OR(>(CAST($2):BIGINT NOT NULL, 100), <=($2, $1))",
      unconvertedRexNodes(1).toString)
  }

  @Test
  def testExtractRefNestedInputFields(): Unit = {
    val rexProgram = buildRexProgramWithNesting()

    val usedFields = RexProgramExtractor.extractRefInputFields(rexProgram)
    val usedNestedFields = RexProgramExtractor.extractRefNestedInputFields(rexProgram, usedFields)

    val expected = Array(Array("amount"), Array("*"))
    assertThat(usedNestedFields, is(expected))
  }

  @Test
  def testExtractRefNestedInputFieldsWithNoNesting(): Unit = {
    val rexProgram = buildSimpleRexProgram()

    val usedFields = RexProgramExtractor.extractRefInputFields(rexProgram)
    val usedNestedFields = RexProgramExtractor.extractRefNestedInputFields(rexProgram, usedFields)

    val expected = Array(Array("*"), Array("*"), Array("*"))
    assertThat(usedNestedFields, is(expected))
  }

  @Test
  def testExtractDeepRefNestedInputFields(): Unit = {
    val rexProgram = buildRexProgramWithDeepNesting()

    val usedFields = RexProgramExtractor.extractRefInputFields(rexProgram)
    val usedNestedFields = RexProgramExtractor.extractRefNestedInputFields(rexProgram, usedFields)

    val expected = Array(
      Array("amount"),
      Array("*"),
      Array("with.deeper.entry", "with.deep.entry"))

    assertThat(usedFields, is(Array(1, 0, 2)))
    assertThat(usedNestedFields, is(expected))
  }

  private def buildRexProgramWithDeepNesting(): RexProgram = {

    // person input
    val passportRow = inputOf(typeFactory)
      .field("id", VARCHAR)
      .field("status", VARCHAR)
      .build

    val personRow = inputOf(typeFactory)
      .field("name", VARCHAR)
      .field("age", INTEGER)
      .nestedField("passport", passportRow)
      .build

    // payment input
    val paymentRow = inputOf(typeFactory)
      .field("id", BIGINT)
      .field("amount", INTEGER)
      .build

    // deep field input
    val deepRowType = inputOf(typeFactory)
      .field("entry", VARCHAR)
      .build

    val entryRowType = inputOf(typeFactory)
      .nestedField("inside", deepRowType)
      .build

    val deeperRowType = inputOf(typeFactory)
      .nestedField("entry", entryRowType)
      .build

    val withRowType = inputOf(typeFactory)
      .nestedField("deep", deepRowType)
      .nestedField("deeper", deeperRowType)
      .build

    val fieldRowType = inputOf(typeFactory)
      .nestedField("with", withRowType)
      .build

    // main input
    val inputRowType = inputOf(typeFactory)
      .nestedField("persons", personRow)
      .nestedField("payments", paymentRow)
      .nestedField("field", fieldRowType)
      .build

    // inputRowType
    //
    // [ persons:  [ name: VARCHAR, age:  INT, passport: [id: VARCHAR, status: VARCHAR ] ],
    //   payments: [ id: BIGINT, amount: INT ],
    //   field:    [ with: [ deep: [ entry: VARCHAR ],
    //                       deeper: [ entry: [ inside: [entry: VARCHAR ] ] ]
    //             ] ]
    // ]

    val builder = new RexProgramBuilder(inputRowType, rexBuilder)

    val t0 = rexBuilder.makeInputRef(personRow, 0)
    val t1 = rexBuilder.makeInputRef(paymentRow, 1)
    val t2 = rexBuilder.makeInputRef(fieldRowType, 2)
    val t3 = rexBuilder.makeExactLiteral(BigDecimal.valueOf(10L))

    // person
    val person$pass = rexBuilder.makeFieldAccess(t0, "passport", false)
    val person$pass$stat = rexBuilder.makeFieldAccess(person$pass, "status", false)

    // payment
    val pay$amount = rexBuilder.makeFieldAccess(t1, "amount", false)
    val multiplyAmount = builder.addExpr(
      rexBuilder.makeCall(SqlStdOperatorTable.MULTIPLY, pay$amount, t3))

    // field
    val field$with = rexBuilder.makeFieldAccess(t2, "with", false)
    val field$with$deep = rexBuilder.makeFieldAccess(field$with, "deep", false)
    val field$with$deeper = rexBuilder.makeFieldAccess(field$with, "deeper", false)
    val field$with$deep$entry = rexBuilder.makeFieldAccess(field$with$deep, "entry", false)
    val field$with$deeper$entry = rexBuilder.makeFieldAccess(field$with$deeper, "entry", false)
    val field$with$deeper$entry$inside = rexBuilder
      .makeFieldAccess(field$with$deeper$entry, "inside", false)
    val field$with$deeper$entry$inside$entry = rexBuilder
      .makeFieldAccess(field$with$deeper$entry$inside, "entry", false)

    builder.addProject(multiplyAmount, "amount")
    builder.addProject(person$pass$stat, "status")
    builder.addProject(field$with$deep$entry, "entry")
    builder.addProject(field$with$deeper$entry$inside$entry, "entry")
    builder.addProject(field$with$deeper$entry, "entry2")
    builder.addProject(t0, "person")

    // Program
    // (
    //   payments.amount * 10),
    //   persons.passport.status,
    //   field.with.deep.entry
    //   field.with.deeper.entry.inside.entry
    //   field.with.deeper.entry
    //   persons
    // )

    builder.getProgram

  }

  private def buildRexProgramWithNesting(): RexProgram = {

    val personRow = inputOf(typeFactory)
      .field("name", INTEGER)
      .field("age", VARCHAR)
      .build

    val paymentRow = inputOf(typeFactory)
      .field("id", BIGINT)
      .field("amount", INTEGER)
      .build

    val types = List(personRow, paymentRow).asJava
    val names = List("persons", "payments").asJava
    val inputRowType = typeFactory.createStructType(types, names)

    val builder = new RexProgramBuilder(inputRowType, rexBuilder)

    val t0 = rexBuilder.makeInputRef(types.get(0), 0)
    val t1 = rexBuilder.makeInputRef(types.get(1), 1)
    val t2 = rexBuilder.makeExactLiteral(BigDecimal.valueOf(100L))

    val payment$amount = rexBuilder.makeFieldAccess(t1, "amount", false)

    builder.addProject(payment$amount, "amount")
    builder.addProject(t0, "persons")
    builder.addProject(t2, "number")
    builder.getProgram
  }

  private def testExtractSinglePostfixCondition(
      fieldIndex: Integer,
      op: SqlPostfixOperator,
      expr: String) : Unit = {

    val inputRowType = typeFactory.createStructType(allFieldTypes, allFieldNames)
    val builder = new RexProgramBuilder(inputRowType, rexBuilder)
    rexBuilder = new RexBuilder(typeFactory)

    // flag
    val t0 = rexBuilder.makeInputRef(allFieldTypes.get(fieldIndex), fieldIndex)
    builder.addCondition(builder.addExpr(rexBuilder.makeCall(op, t0)))

    val program = builder.getProgram(false)
    val relBuilder: RexBuilder = new RexBuilder(typeFactory)
    val (convertedExpressions, unconvertedRexNodes) =
      RexProgramExtractor.extractConjunctiveConditions(
        program,
        relBuilder,
        functionCatalog)

    assertEquals(1, convertedExpressions.length)
    assertEquals(expr, convertedExpressions.head.toString)
    assertEquals(0, unconvertedRexNodes.length)
  }

  private def assertExpressionArrayEquals(
      expected: Array[Expression],
      actual: Array[Expression]) = {
    val sortedExpected = expected.sortBy(e => e.toString)
    val sortedActual = actual.sortBy(e => e.toString)

    assertEquals(sortedExpected.length, sortedActual.length)
    sortedExpected.zip(sortedActual).foreach {
      case (l, r) => assertEquals(l.toString, r.toString)
    }
  }
}
