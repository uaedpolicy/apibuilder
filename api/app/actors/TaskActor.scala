package actors

import lib.{ServiceDiff, Text}
import com.gilt.apidoc.api.v0.models.{Publication, Version}
import com.gilt.apidoc.internal.v0.models.{Difference, DifferenceBreaking, DifferenceNonBreaking, DifferenceUndefinedType}
import com.gilt.apidoc.internal.v0.models.{Task, TaskDataDiffVersion, TaskDataIndexApplication, TaskDataUndefinedType}
import db.{ApplicationsDao, Authorization, ChangesDao, OrganizationsDao, TasksDao, UsersDao, VersionsDao}
import play.api.Logger
import akka.actor.Actor
import java.util.UUID

object TaskActor {

  object Messages {
    case class TaskCreated(guid: UUID)
    case object RestartDroppedTasks
    case object PurgeOldTasks
    case object NotifyFailed
  }

}

class TaskActor extends Actor {

  private val NumberDaysBeforePurge = 90

  def receive = {

    case TaskActor.Messages.TaskCreated(guid) => Util.withVerboseErrorHandler(
      s"TaskActor.Messages.TaskCreated($guid)", {
        TasksDao.findByGuid(guid).map { task =>
          TasksDao.incrementNumberAttempts(UsersDao.AdminUser, task)

          task.data match {
            case TaskDataDiffVersion(oldVersionGuid, newVersionGuid) => {
              VersionsDao.findByGuid(Authorization.All, oldVersionGuid, isDeleted = None).map { oldVersion =>
                VersionsDao.findByGuid(Authorization.All, newVersionGuid, isDeleted = None).map { newVersion =>
                  ServiceDiff(oldVersion.service, newVersion.service).differences match {
                    case Nil => {
                      // No-op
                    }
                    case diffs => {
                      ChangesDao.upsert(UsersDao.AdminUser, oldVersion, newVersion, diffs)
                      versionUpdated(oldVersion, newVersion, diffs)
                    }
                  }
                }
              }
              TasksDao.softDelete(UsersDao.AdminUser, task)
            }

            case TaskDataIndexApplication(applicationGuid) => {
              println(" - TaskDataIndexApplication")
              Search.indexApplication(applicationGuid)
              TasksDao.softDelete(UsersDao.AdminUser, task)
            }

            case TaskDataUndefinedType(desc) => {
              TasksDao.recordError(UsersDao.AdminUser, task, "Task actor got an undefined data type: " + desc)
            }
          }
        }
      }
    )

    case TaskActor.Messages.RestartDroppedTasks => Util.withVerboseErrorHandler(
      "TaskActor.Messages.RestartDroppedTasks", {
        TasksDao.findAll(
          nOrFewerAttempts = Some(2),
          nOrMoreMinutesOld = Some(1)
        ).foreach { task =>
          global.Actors.mainActor ! actors.MainActor.Messages.TaskCreated(task.guid)
        }
      }
    )

    case TaskActor.Messages.NotifyFailed => Util.withVerboseErrorHandler(
      "TaskActor.Messages.NotifyFailed", {
        val errors = TasksDao.findAll(
          nOrMoreAttempts = Some(2)
        ).map { task =>
          val errorType = task.data match {
            case TaskDataDiffVersion(_, _) => "Diff version"
            case TaskDataIndexApplication(_) => "Index version"
            case TaskDataUndefinedType(desc) => desc
          }

          val errorMsg = Text.truncate(task.lastError.getOrElse("No information on error"))
          s"$errorType task ${task.guid}: $errorMsg"
        }
        Emails.sendErrors(
          subject = "One or more tasks failed",
          errors = errors
        )
      }
    )

    case TaskActor.Messages.PurgeOldTasks => Util.withVerboseErrorHandler(
      "TaskActor.Messages.PurgeOldTasks", {
        TasksDao.findAll(
          isDeleted = Some(true),
          deletedAtLeastNDaysAgo = Some(NumberDaysBeforePurge)
        ).foreach { task =>
          TasksDao.purge(UsersDao.AdminUser, task)
        }
      }
    )

    case m: Any => {
      Logger.error("Task actor got an unhandled message: " + m)
    }

  }

  private def versionUpdated(
    oldVersion: Version,
    newVersion: Version,
    diff: Seq[Difference]
  ) {
    ApplicationsDao.findAll(Authorization.All, version = Some(newVersion), limit = 1).headOption.map { application =>
      OrganizationsDao.findAll(Authorization.All, application = Some(application), limit = 1).headOption.map { org =>
        Emails.deliver(
          org = org,
          publication = Publication.VersionsCreate,
          subject = s"${org.name}/${application.name}: New Version Uploaded (${newVersion.version}) ",
          body = views.html.emails.versionCreated(
            org,
            application,
            newVersion,
            oldVersion = Some(oldVersion),
            breakingChanges = diff.filter { d =>
              d match {
                case DifferenceBreaking(desc) => true
                case DifferenceNonBreaking(desc) => false
                case DifferenceUndefinedType(desc) => false
              }
            }.map(_.asInstanceOf[DifferenceBreaking]),
            nonBreakingChanges = diff.filter { d =>
              d match {
                case DifferenceBreaking(desc) => false
                case DifferenceNonBreaking(desc) => true
                case DifferenceUndefinedType(desc) => false
              }
            }.map(_.asInstanceOf[DifferenceNonBreaking])
          ).toString
        )
      }
    }
  }

}
