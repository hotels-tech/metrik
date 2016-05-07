package com.searchlight.khronus.influx.service

import akka.actor.ActorSystem
import com.searchlight.khronus.model.{ Counter, Metric }
import com.searchlight.khronus.query.DynamicSQLQueryService
import com.searchlight.khronus.store.MetaStore
import com.searchlight.khronus.util.JacksonJsonSupport
import com.typesafe.config.ConfigFactory
import org.mockito.Mockito
import org.scalatest.mock.MockitoSugar
import org.specs2.matcher.MatchResult
import org.specs2.mutable.Specification
import spray.http.StatusCodes._
import spray.http._
import spray.routing.HttpService
import spray.testkit.Specs2RouteTest

import concurrent.duration._

class InfluxServiceSpec extends Specification with MockitoSugar with HttpService with Specs2RouteTest with JacksonJsonSupport {

  def actorRefFactory = ActorSystem("TestSystem", ConfigFactory.parseString(
    """
      |akka {
      |  loggers = ["akka.event.slf4j.Slf4jLogger"]
      |  loglevel = INFO
      |  stdout-loglevel = DEBUG
      |  actor {
      |    provider = "akka.actor.LocalActorRefProvider"
      |  }
      | }
      |
    """.stripMargin))
  override def createActorSystem(): ActorSystem = actorRefFactory

  implicit def default(implicit system: ActorSystem) = RouteTestTimeout(new DurationInt(5).second)

  val influxSeriesURI = "/series"

  class MockedInfluxEndpoint extends InfluxEndpoint {
    override lazy val actorRefFactory = system

    override lazy val metaStore: MetaStore = mock[MetaStore]

    override lazy val dynamicSQLQueryService = mock[DynamicSQLQueryService]

  }

  def applying[T](f: () ⇒ MatchResult[_]) = f()

  "InfluxService with error requests" should {
    "leave GET requests to other paths unhandled" in {
      applying {
        () ⇒
          Get("/kermit") ~> new MockedInfluxEndpoint().influxServiceRoute ~> check {
            handled must beFalse
          }
      }
    }

    "return a MethodNotAllowed error for PUT requests to the path" in {
      applying {
        () ⇒
          {
            val uri = Uri(influxSeriesURI).withQuery("q" -> "Some query")
            Put(uri) ~> sealRoute(new MockedInfluxEndpoint().influxServiceRoute) ~> check {
              status === MethodNotAllowed
              println(response.message)
              response.message.entity.asString === "HTTP method not allowed, supported methods: GET, OPTIONS"
            }
          }
      }
    }

  }

  "InfluxService for listing series" should {
    val listSeriesURI = Uri(influxSeriesURI).withQuery("q" -> "list series /counter/", "u" -> "aUser", "p" -> "****")

    "return all existent metrics as influx series" in {
      applying {
        () ⇒
          {
            val instance = new MockedInfluxEndpoint()

            val counter = Metric("counter1", Counter)
            val searchExpression = ".*counter.*"

            Mockito.when(instance.metaStore.searchInSnapshotByRegex(searchExpression)).thenReturn(Seq(counter))
            Get(listSeriesURI) ~> instance.influxServiceRoute ~> check {
              response.isResponse must beTrue
              status == OK

              Mockito.verify(instance.metaStore).searchInSnapshotByRegex(searchExpression)

              val results = responseAs[Seq[InfluxSeries]]
              results.size must beEqualTo(1)

              val series = results.head
              series.name === "list_series_result"
              series.columns.size mustEqual 2
              series.points.size mustEqual 1
              series.points(0)(1) mustEqual counter.name
            }
          }
      }
    }

  }

}
