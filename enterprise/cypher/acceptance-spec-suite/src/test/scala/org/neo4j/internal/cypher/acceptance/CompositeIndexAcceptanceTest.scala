/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.internal.cypher.acceptance

import org.neo4j.cypher.ExecutionEngineFunSuite
import org.neo4j.graphdb.Node
import org.neo4j.kernel.GraphDatabaseQueryService
import org.scalatest.matchers.{MatchResult, Matcher}

import scala.collection.JavaConverters._

/**
  * These tests are testing the actual index implementation, thus they should all check the actual result.
  * If you only want to verify that plans using indexes are actually planned, please use
  * [[org.neo4j.cypher.internal.compiler.v3_3.planner.logical.LeafPlanningIntegrationTest]]
  */
class CompositeIndexAcceptanceTest extends ExecutionEngineFunSuite with CypherComparisonSupport {

  test("should succeed in creating and deleting composite index") {
    // When
    graph.createIndex("Person", "firstname")
    graph.createIndex("Person", "firstname","lastname")

    // Then
    graph should haveIndexes(":Person(firstname)")
    graph should haveIndexes(":Person(firstname)", ":Person(firstname,lastname)")

    // When
    succeedWith(Configs.Procs, "DROP INDEX ON :Person(firstname , lastname)")

    // Then
    graph should haveIndexes(":Person(firstname)")
    graph should not(haveIndexes(":Person(firstname,lastname)"))
  }

  test("should use composite index when all predicates are present") {
    // Given
    graph.createIndex("User", "firstname","lastname")
    val n1 = createLabeledNode(Map("firstname" -> "Joe", "lastname" -> "Soap"), "User")
    val n2 = createLabeledNode(Map("firstname" -> "Joe", "lastname" -> "Smoke"), "User")
    val n3 = createLabeledNode(Map("firstname" -> "Jake", "lastname" -> "Soap"), "User")

    // When
    val result = succeedWith(Configs.Interpreted, "MATCH (n:User) WHERE n.lastname = 'Soap' AND n.firstname = 'Joe' RETURN n")

    // Then
    result should use("NodeIndexSeek")
    result.toComparableResult should equal(List(Map("n" -> n1)))
  }

  test("should not use composite index when not all predicates are present") {
    // Given
    graph.createIndex("User", "firstname","lastname")
    val n1 = createLabeledNode(Map("firstname" -> "Joe", "lastname" -> "Soap"), "User")
    val n2 = createLabeledNode(Map("firstname" -> "Joe", "lastname" -> "Smoke"), "User")
    val n3 = createLabeledNode(Map("firstname" -> "Jake", "lastname" -> "Soap"), "User")
    for (i <- 1 to 100) {
      createLabeledNode(Map("firstname" -> "Joe"), "User")
      createLabeledNode(Map("lastname" -> "Soap"), "User")
    }

    // When
    val result = succeedWith(Configs.AbsolutelyAll - Configs.Procs, "MATCH (n:User) WHERE n.firstname = 'Jake' RETURN n")

    // Then
    result should not(use("NodeIndexSeek"))
    result.toComparableResult should equal(List(Map("n" -> n3)))
  }

  test("should use composite index when all predicates are present even in competition with other single property indexes with similar cardinality") {
    // Given
    graph.createIndex("User", "firstname")
    graph.createIndex("User", "lastname")
    graph.createIndex("User", "firstname","lastname")
    val n1 = createLabeledNode(Map("firstname" -> "Joe", "lastname" -> "Soap"), "User")
    val n2 = createLabeledNode(Map("firstname" -> "Joe", "lastname" -> "Smoke"), "User")
    val n3 = createLabeledNode(Map("firstname" -> "Jake", "lastname" -> "Soap"), "User")
    for (i <- 1 to 100) {
      createLabeledNode("User")
      createLabeledNode("User")
    }

    // When
    val result = succeedWith(Configs.Interpreted, "MATCH (n:User) WHERE n.lastname = 'Soap' AND n.firstname = 'Joe' RETURN n")

    // Then
    result should useIndex(":User(firstname,lastname)")
    result should not(useIndex(":User(firstname)"))
    result should not(useIndex(":User(lastname)"))
    result.toComparableResult should equal(List(Map("n" -> n1)))
  }

  test("should not use composite index when index hint for alternative index is present") {
    // Given
    graph.createIndex("User", "firstname")
    graph.createIndex("User", "lastname")
    graph.createIndex("User", "firstname","lastname")
    val n1 = createLabeledNode(Map("firstname" -> "Joe", "lastname" -> "Soap"), "User")
    val n2 = createLabeledNode(Map("firstname" -> "Joe", "lastname" -> "Smoke"), "User")
    val n3 = createLabeledNode(Map("firstname" -> "Jake", "lastname" -> "Soap"), "User")
    for (i <- 1 to 100) {
      createLabeledNode("User")
      createLabeledNode("User")
    }

    // When
    val result = succeedWith(Configs.All,
      """
        |MATCH (n:User)
        |USING INDEX n:User(lastname)
        |WHERE n.lastname = 'Soap' AND n.firstname = 'Joe'
        |RETURN n
        |""".stripMargin)

    // Then
    result should not(useIndex(":User(firstname,lastname)"))
    result should not(useIndex(":User(firstname)"))
    result should useIndex(":User(lastname)")
    result.toComparableResult should equal(List(Map("n" -> n1)))
  }

  test("should use composite index with combined equality and existence predicates") {
    // Given
    graph.createIndex("User", "firstname")
    graph.createIndex("User", "lastname")
    graph.createIndex("User", "firstname", "lastname")
    val n1 = createLabeledNode(Map("firstname" -> "Joe", "lastname" -> "Soap"), "User")
    val n2 = createLabeledNode(Map("firstname" -> "Joe", "lastname" -> "Smoke"), "User")
    val n3 = createLabeledNode(Map("firstname" -> "Jake", "lastname" -> "Soap"), "User")
    for (i <- 1 to 100) {
      createLabeledNode("User")
      createLabeledNode("User")
    }

    // When
    val result = succeedWith(Configs.Interpreted, "MATCH (n:User) WHERE exists(n.lastname) AND n.firstname = 'Jake' RETURN n")

    // Then
    result should not(useIndex(":User(firstname,lastname)")) // TODO: This should change once scans of indexes is supported
    result should not(useIndex(":User(firstname)"))
    result should not(useIndex(":User(lastname)"))
    result.toComparableResult should equal(List(Map("n" -> n3)))
  }

  test("should be able to update composite index when only one property has changed") {
    graph.createIndex("Person", "firstname", "lastname")
    val n = graph.execute("CREATE (n:Person {firstname:'Joe', lastname:'Soap'}) RETURN n").columnAs("n").next().asInstanceOf[Node]
    graph.execute("MATCH (n:Person) SET n.lastname = 'Bloggs'")
    val result = succeedWith(Configs.Interpreted, "MATCH (n:Person) where n.firstname = 'Joe' and n.lastname = 'Bloggs' RETURN n")
    result should use("NodeIndexSeek")
    result.toComparableResult should equal(List(Map("n" -> n)))
  }

  test("should plan a composite index seek for a multiple property predicate expression when index is created after data") {
    succeedWith(Configs.CommunityInterpreted - Configs.Cost2_3, "WITH RANGE(0,10) AS num CREATE (:Person {id:num})") // ensure label cardinality favors index
    succeedWith(Configs.Interpreted - Configs.Cost2_3, "CREATE (n:Person {firstname:'Joe', lastname:'Soap'})")
    graph.createIndex("Person", "firstname")
    graph.createIndex("Person", "firstname", "lastname")
    val result = succeedWith(Configs.Interpreted, "MATCH (n:Person) WHERE n.firstname = 'Joe' AND n.lastname = 'Soap' RETURN n")
    result should useIndex(":Person(firstname,lastname)")
  }

  test("should use composite index correctly given two IN predicates") {
    // Given
    graph.createIndex("Foo", "bar", "baz")

    (0 to 9) foreach { bar =>
      (0 to 9) foreach { baz =>
        createLabeledNode(Map("bar" -> bar, "baz" -> baz, "idx" -> (bar * 10 + baz)), "Foo")
      }
    }

    // When
    val result = succeedWith(Configs.CommunityInterpreted,
      """MATCH (n:Foo)
        |WHERE n.bar IN [0,1,2,3,4,5,6,7,8,9]
        |  AND n.baz IN [0,1,2,3,4,5,6,7,8,9]
        |RETURN n.idx as x
        |ORDER BY x""".stripMargin)

    // Then
    result should useIndex(":Foo(bar,baz)")
    result.toComparableResult should equal((0 to 99).map(i => Map("x" -> i)).toList)
  }

  test("should use composite index correctly with a single exact together with a List of seeks") {
    // Given
    graph.createIndex("Foo", "bar", "baz")

    (0 to 9) foreach { baz =>
      createLabeledNode(Map("bar" -> 1, "baz" -> baz), "Foo")
    }

    // When
    val result = succeedWith(Configs.CommunityInterpreted,
      """MATCH (n:Foo)
        |WHERE n.bar = 1
        |  AND n.baz IN [0,1,2,3,4,5,6,7,8,9]
        |RETURN n.baz as x
        |ORDER BY x""".stripMargin)

    // Then
    result should useIndex(":Foo(bar,baz)")
    result.toComparableResult should equal((0 to 9).map(i => Map("x" -> i)).toList)
  }

  test("should use composite index correctly with a single exact together with a List of seeks, with the exact seek not being first") {
    // Given
    graph.createIndex("Foo", "bar", "baz")

    (0 to 9) foreach { baz =>
      createLabeledNode(Map("baz" -> (baz % 2), "bar" -> baz), "Foo")
    }

    // When
    val result = succeedWith(Configs.CommunityInterpreted,
      """MATCH (n:Foo)
        |WHERE n.baz = 1
        |  AND n.bar IN [0,1,2,3,4,5,6,7,8,9]
        |RETURN n.bar as x
        |ORDER BY x""".stripMargin)

    // Then
    result should useIndex(":Foo(bar,baz)")
    result.toComparableResult should equal(List(Map("x" -> 1), Map("x" -> 3), Map("x" -> 5), Map("x" -> 7), Map("x" -> 9)))
  }

  test("should handle missing properties when populating index") {
    // Given
    createLabeledNode(Map("foo" -> 42), "PROFILES")
    createLabeledNode(Map("foo" -> 42, "bar" -> 1337, "baz" -> 1980), "L")

    // When
    graph.createIndex("L", "foo", "bar", "baz")

    // Then
    graph should haveIndexes(":L(foo,bar,baz)")
    val result = succeedWith(Configs.CommunityInterpreted, "MATCH (n:L {foo: 42, bar: 1337, baz: 1980}) RETURN count(n)")
    result.toComparableResult should equal(Seq(Map("count(n)" -> 1)))
    result should useIndex(":L(foo,bar,baz")
  }

  test("should handle missing properties when adding to index after creation") {
    // Given
    createLabeledNode(Map("foo" -> 42), "PROFILES")

    // When
    graph.createIndex("L", "foo", "bar", "baz")
    createLabeledNode(Map("foo" -> 42, "bar" -> 1337, "baz" -> 1980), "L")

    // Then
    graph should haveIndexes(":L(foo,bar,baz)")
    val result = succeedWith(Configs.CommunityInterpreted, "MATCH (n:L {foo: 42, bar: 1337, baz: 1980}) RETURN count(n)")
    result.toComparableResult should equal(Seq(Map("count(n)" -> 1)))
    result should useIndex(":L(foo,bar,baz)")
  }

  test("should not fail on multiple attempts to create a composite index") {
    // Given
    succeedWith(Configs.Procs, "CREATE INDEX ON :Person(firstname, lastname)")
    succeedWith(Configs.Procs, "CREATE INDEX ON :Person(firstname, lastname)")
  }

  test("should not use range queries against a composite index") {
    // Given
    graph.createIndex("X", "p1", "p2")
    val n = createLabeledNode(Map("p1" -> 1, "p2" -> 1), "X")

    // When
    val result = succeedWith(Configs.Interpreted, "match (n:X) where n.p1 = 1 AND n.p2 > 0 return n;")

    // Then
    result.toComparableResult should equal(Seq(Map("n" -> n)))
    result shouldNot useIndex(":X(p1,p2)")
  }

  test("should not use composite index for range, prefix, contains and exists predicates") {
    // Given
    graph.createIndex("User", "name", "surname", "age", "active")
    val n = createLabeledNode(Map("name" -> "joe", "surname" -> "soap", "age" -> 25, "active" -> true), "User")

    // For all combinations
    Seq(
      (Configs.Interpreted, "n.name = 'joe' AND n.surname = 'soap' AND n.age = 25 AND n.active = true", true),         // all equality
      (Configs.Interpreted, "n.surname = 'soap' AND n.age = 25 AND n.active = true AND n.name = 'joe'", true),         // different order
      (Configs.CommunityInterpreted, "n.name = 'joe' AND n.surname = 'soap' AND n.age = 25 AND exists(n.active)", false),       // exists()
      (Configs.Interpreted, "n.name = 'joe' AND n.surname = 'soap' AND n.age >= 25 AND n.active = true", false),       // inequality
      (Configs.Interpreted, "n.name = 'joe' AND n.surname STARTS WITH 's' AND n.age = 25 AND n.active = true", false), // prefix
      (Configs.Interpreted, "n.name = 'joe' AND n.surname ENDS WITH 'p' AND n.age = 25 AND n.active = true", false),   // suffix
      (Configs.Interpreted, "n.name >= 'i' AND n.surname = 'soap' AND n.age = 25 AND n.active = true", false),         // inequality first
      (Configs.Interpreted, "n.name STARTS WITH 'j' AND n.surname = 'soap' AND n.age = 25 AND n.active = true", false),// prefix first
      (Configs.Interpreted, "n.name CONTAINS 'j' AND n.surname = 'soap' AND n.age = 25 AND n.active = true", false),   // contains first
      (Configs.CommunityInterpreted, "n.name = 'joe' AND n.surname STARTS WITH 'soap' AND n.age <= 25 AND exists(n.active)", false) // combination: equality, prefix, inequality, exists()
    ).foreach {
      case (testConfig, predicates, valid) =>

        // When
        val query = s"MATCH (n:User) WHERE $predicates RETURN n"
        val result = try {
          succeedWith(testConfig, query)
        } catch {
          case e: Exception =>
            System.err.println(query)
            throw e
        }
        try {

          // Then
          result.toComparableResult should equal(Seq(Map("n" -> n)))
          if (valid)
            result should useIndex(":User(name,surname,age,active)")
          else
            result shouldNot useIndex(":User(name,surname,age,active)")
        } catch {
          case e: Exception =>
            System.err.println(s"Failed for predicates: $predicates")
            System.err.println(result.executionPlanDescription())
            throw e
        }
    }
  }

  test("nested index join with composite indexes") {
    // given
    graph.createIndex("X", "p1", "p2")
    (1 to 1000) foreach { _ => // Get the planner to do what we expect it to!
      createLabeledNode("X")
    }
    val a = createNode("p1" -> 1, "p2" -> 1)
    val b = createLabeledNode(Map("p1" -> 1, "p2" -> 1), "X")

    // 2.3 excluded because the params syntax was not supported in that version
    val result = succeedWith(Configs.CommunityInterpreted - Configs.Version2_3, "match (a), (b:X) where id(a) = $id AND b.p1 = a.p1 AND b.p2 = 1 return b",
      "id" -> a.getId)

    result.toComparableResult should equal(Seq(Map("b" -> b)))
    result should useIndex(":X(p1,p2)")
  }

  case class haveIndexes(expectedIndexes: String*) extends Matcher[GraphDatabaseQueryService] {
    def apply(graph: GraphDatabaseQueryService): MatchResult = {
      graph.inTx {
        val indexNames = graph.schema().getIndexes.asScala.toList.map(i => s":${i.getLabel}(${i.getPropertyKeys.asScala.toList.mkString(",")})")
        val result = expectedIndexes.forall(i => indexNames.contains(i.toString))
        MatchResult(
          result,
          s"Expected graph to have indexes ${expectedIndexes.mkString(", ")}, but it was ${indexNames.mkString(", ")}",
          s"Expected graph to not have indexes ${expectedIndexes.mkString(", ")}, but it did."
        )
      }
    }
  }
}
