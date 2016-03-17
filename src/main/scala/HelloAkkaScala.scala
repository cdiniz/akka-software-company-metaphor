import akka.actor.SupervisorStrategy.{Escalate, Resume, Directive}
import akka.actor._
import scala.collection.mutable
import scala.concurrent.{Await, Future, ExecutionContext}
import scala.concurrent.duration._
import akka.routing.{ ActorRefRoutee, RoundRobinRoutingLogic, Router }

case class Task(id : Int, title : String, description : String, timeEstimate : Int)
case class Project(title : String, backlog : Seq[Task])
case class TaskDone(id : Int, timeSpent : Int)
case class ProjectDone(timeSpent : Int)
case class ImSickException(msg: String, currentTask : Int, partialTimeSpent : Int) extends Exception(msg)
case object ImOnIt
case object ImBusy


class SoftwareEngineer extends Actor with ActorLogging{
  var tasksDone = 0
  def receive = {
    case Task(id,title,desc,timeEstimate) =>
      if (scala.util.Random.nextInt(31) == 1)
        throw new ImSickException("WITH THE FLU", id, scala.util.Random.nextInt(500))
      tasksDone = tasksDone + 1
      context.parent ! TaskDone(id,timeEstimate + scala.util.Random.nextInt(timeEstimate))
  }
}

class TeamLeader(teamSize : Int) extends Actor with ActorLogging {
  var project : Option[Project] = None
  var tasksDone : Seq[Int] = Seq()
  var reportTo : Option[ActorRef] = None
  var projectTime = 0
  
  var router = {
    val routees = Vector.fill(teamSize) {
      val r = context.actorOf(Props[SoftwareEngineer])
      context watch r
      ActorRefRoutee(r)
    }
    Router(RoundRobinRoutingLogic(), routees)
  }

  def receive = {
    case Project(title,backlog) =>
      project = Some(Project(title,backlog))
      reportTo = Some(sender)
      sender ! ImOnIt
      context.become(managing)
      log.debug("New Project, Let's delegate!")
      backlog.foreach(router.route(_, sender()))
  }

  def managing : Receive = {
    case TaskDone(id,timeSpent) =>
      log.debug(s"Task Done $id")
      tasksDone = tasksDone :+ id
      projectTime = projectTime + timeSpent
      if (tasksDone.length == project.fold(0)(_.backlog.length)){
        log.debug(s"Project ${project.fold("Unknown")(_.title)} done!")
        log.debug(s"Project Time Estimate: ${project.fold(0)(p => p.backlog.map(_.timeEstimate).sum)}, Real Spent Time: $projectTime")
        reportTo.get ! ProjectDone(projectTime)
        project = None
        tasksDone = Seq()
        projectTime = 0
        context.become(receive)
      }

    case Project(title,backlog) => sender ! ImBusy

  }

  val decider: PartialFunction[Throwable, Directive] = {
    case ImSickException(msg,task,partialTimeSpent) =>
      projectTime = projectTime + partialTimeSpent
      router.route(project.get.backlog.find(_.id == task).get, sender())
      Resume //Instead of restart
  }
  override def supervisorStrategy: SupervisorStrategy =
    OneForOneStrategy()(decider.orElse(SupervisorStrategy.defaultStrategy.decider))

}

object HelloAkkaScala extends App {

  // Create the 'Software Company'
  val system = ActorSystem("SoftwareCompany")

  // Create a leader, with a 5 element team
  val leader = system.actorOf(Props(new TeamLeader(5)), "Leader")

  // Create the board inbox
  val board = Inbox.create(system)

  //Create sample projects
  val project = Project("simple project 1",Seq(Task(1,"1","1",100),Task(2,"2","2",1440),Task(3,"3","3",1330),Task(4,"4","4",1100),Task(5,"5","5",700)))

  //send a project to a leader
  board.send(leader, project)

board.receive(5.second) match {
    case ImOnIt => println("Please keep the time ")
    case ImBusy => println("should be joking with me")
  }

  board.receive(30.second) match {
    case ProjectDone(timeSpent) => 
    if (timeSpent > project.backlog.map(_.timeEstimate).sum)
      println("These software engineers spent all days at reddit for sure!")
    else  
      println("Nicely done!")
    system.terminate()
  }

  Await.result(system.whenTerminated, Duration.Inf)

}
