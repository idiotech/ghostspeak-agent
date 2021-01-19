package tw.idv.idiotech.ghostspeak.agent

import akka.actor.typed.{ ActorRef, ActorSystem }
import akka.util.Timeout
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import akka.http.scaladsl.unmarshalling._
import io.circe.Decoder

import scala.concurrent.duration._
import scala.concurrent.Future

class JobRoutes(buildJobRepository: ActorRef[JobRepository.Command])(
  implicit system: ActorSystem[_]
) extends FailFastCirceSupport {

  import akka.actor.typed.scaladsl.AskPattern.schedulerFromActorSystem
  import akka.actor.typed.scaladsl.AskPattern.Askable

  // asking someone requires a timeout and a scheduler, if the timeout hits without response
  // the ask is failed with a TimeoutException
  implicit val timeout: Timeout = 3.seconds

  implicit def marsh: FromRequestUnmarshaller[JobRepository.Job] = ???

  lazy val theJobRoutes: Route =
    pathPrefix("jobs") {
      concat(
        pathEnd {
          concat(
            post {
              entity(as[JobRepository.Job]) { job =>
                val operationPerformed: Future[JobRepository.Response] =
                  buildJobRepository.ask(JobRepository.AddJob(job, _))
                onSuccess(operationPerformed) {
                  case JobRepository.OK => complete("Job added")
                  case JobRepository.KO(reason) =>
                    complete(StatusCodes.InternalServerError -> reason)
                }
              }
            },
            delete {
              val operationPerformed: Future[JobRepository.Response] =
                buildJobRepository.ask(JobRepository.ClearJobs)
              onSuccess(operationPerformed) {
                case JobRepository.OK         => complete("Jobs cleared")
                case JobRepository.KO(reason) => complete(StatusCodes.InternalServerError -> reason)
              }
            }
          )
        }
//        ,
//        (get & path(LongNumber)) { id =>
//          val maybeJob: Future[Option[JobRepository.Job]] =
//            buildJobRepository.ask(JobRepository.GetJobById(id, _))
//          rejectEmptyResponse {
//            complete(maybeJob)
//          }
//        }
      )
    }
}
