import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.Http
import akka.http.client.RequestBuilding
import akka.http.marshallers.sprayjson.SprayJsonSupport._
import akka.http.marshalling.ToResponseMarshallable
import akka.http.model.{HttpResponse, HttpRequest}
import akka.http.model.StatusCodes._
import akka.http.server.Directives._
import akka.http.unmarshalling.Unmarshal
import akka.stream.FlowMaterializer
import akka.stream.scaladsl.{Sink, Source}
import com.typesafe.config.ConfigFactory
import java.io.IOException
import scala.concurrent.Future
import scala.math._
import spray.json.DefaultJsonProtocol

case class IpInfo(ip: String, country: Option[String], city: Option[String], latitude: Option[Double], longitude: Option[Double])

case class IpPairSummaryRequest(ip1: String, ip2: String)

case class IpPairSummary(distance: Option[Double], ip1Info: IpInfo, ip2Info: IpInfo)

object IpPairSummary extends DefaultJsonProtocol {
  def apply(ip1Info: IpInfo, ip2Info: IpInfo): IpPairSummary = {
    IpPairSummary(calculateDistance(ip1Info, ip2Info), ip1Info, ip2Info)
  }

  private def calculateDistance(ip1Info: IpInfo, ip2Info: IpInfo): Option[Double] = {
    (ip1Info.latitude, ip1Info.longitude, ip2Info.latitude, ip2Info.longitude) match {
      case (Some(lat1), Some(lon1), Some(lat2), Some(lon2)) =>
        // see http://www.movable-type.co.uk/scripts/latlong.html
        val φ1 = toRadians(lat1)
        val φ2 = toRadians(lat2)
        val Δφ = toRadians(lat2 - lat1)
        val Δλ = toRadians(lon2 - lon1)
        val a = pow(sin(Δφ / 2), 2) + cos(φ1) * cos(φ2) * pow(sin(Δλ / 2), 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        Option(EarthRadius * c)
      case _ => None
    }
  }

  private val EarthRadius = 6371.0
}

trait Protocols extends DefaultJsonProtocol {
  implicit val ipInfoFormat = jsonFormat5(IpInfo.apply)
  implicit val ipPairSummaryRequestFormat = jsonFormat2(IpPairSummaryRequest.apply)
  implicit val ipPairSummaryFormat = jsonFormat3(IpPairSummary.apply)
}

object AkkaHttpMicroservice extends App with Protocols {
  implicit val actorSystem = ActorSystem()
  implicit val materializer = FlowMaterializer()
  implicit val dispatcher = actorSystem.dispatcher

  val config = ConfigFactory.load()
  val logger = Logging(actorSystem, getClass)

  val telizeConnection = Http().outgoingConnection(config.getString("services.telizeHost"), config.getInt("services.telizePort"))

  def telizeRequest(request: HttpRequest): Future[HttpResponse] = Source.single(request).via(telizeConnection.flow).runWith(Sink.head)

  def fetchIpInfo(ip: String): Future[Either[String, IpInfo]] = {
    telizeRequest(RequestBuilding.Get(s"/geoip/$ip")).flatMap { response =>
      response.status match {
        case OK => Unmarshal(response.entity).to[IpInfo].map(Right(_))
        case BadRequest => Future.successful(Left(s"$ip: incorrect IP format"))
        case _ => Unmarshal(response.entity).to[String].flatMap { entity =>
          val error = s"Telize request failed with status code ${response.status} and entity $entity"
          logger.error(error)
          Future.failed(new IOException(error))
        }
      }
    }
  }

  Http().bind(interface = config.getString("http.interface"), port = config.getInt("http.port")).startHandlingWith {
    logRequestResult("akka-http-microservice") {
      pathPrefix("ip") {
        (get & path(Segment)) { ip =>
          complete {
            fetchIpInfo(ip).map {
              case Right(ipInfo) => ToResponseMarshallable(OK -> ipInfo)
              case Left(errorMessage) => ToResponseMarshallable(BadRequest -> errorMessage)
            }
          }
        } ~
        (post & entity(as[IpPairSummaryRequest])) { ipPairSummaryRequest =>
          complete {
            val ip1InfoFuture = fetchIpInfo(ipPairSummaryRequest.ip1)
            val ip2InfoFuture = fetchIpInfo(ipPairSummaryRequest.ip2)
            ip1InfoFuture.flatMap { ip1Info =>
              ip2InfoFuture.map { ip2Info =>
                (ip1Info, ip2Info) match {
                  case (Right(info1), Right(info2)) => ToResponseMarshallable(OK -> IpPairSummary(info1, info2))
                  case (Left(errorMessage), _) => ToResponseMarshallable(BadRequest -> errorMessage)
                  case (_, Left(errorMessage)) => ToResponseMarshallable(BadRequest -> errorMessage)
                }
              }
            }
          }
        }
      }
    }
  }
}
