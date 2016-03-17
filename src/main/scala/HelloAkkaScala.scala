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


//Requirements
//a CEO MULTIPLE TEAMLEADERS DO DELEGATE PROJECTS
//TEAM LEADERS HAVE TEAMS OF MULTIPLE SOFTWARE ENGINEERS
//SOFTWARE ENGINEERS CAN GET SICK

class SoftwareEngineer extends Actor with ActorLogging{
  var totalTimeSpent = 0
  var tasksDone = 0
  def receive = {
    case Task(id,title,desc,timeEstimate) =>
      if (scala.util.Random.nextInt(31) == 1)
        throw new ImSickException("WITH THE FLU", id, scala.util.Random.nextInt(500))
      val timeSpentOnHackerNews =  scala.util.Random.nextInt(500)
      val optimisticEstimateTimeCompensation =  scala.util.Random.nextInt(timeEstimate)
      val totalTimeSpentOnTask = timeSpentOnHackerNews + optimisticEstimateTimeCompensation + timeEstimate
      totalTimeSpent = totalTimeSpentOnTask + totalTimeSpent
      tasksDone = tasksDone + 1
      log.debug(s"Task $id Done! Estimate: $timeEstimate RealTime: $totalTimeSpentOnTask")
      context.parent ! TaskDone(id,totalTimeSpentOnTask)
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
    case Terminated(a) =>
      log.debug(s"Recover $a")
      router = router.removeRoutee(a)
      val r = context.actorOf(Props[SoftwareEngineer])
      context watch r
      router = router.addRoutee(r)
  }

  def managing : Receive = {
    case TaskDone(id,timeSpent) =>
      log.debug(s"Task Done $id")
      tasksDone = tasksDone :+ id
      projectTime = projectTime + timeSpent
      if (tasksDone.length == project.fold(0)(_.backlog.length)){
        log.debug(s"Project ${project.fold("Unknown")(_.title)} done!")
        log.debug(s"Project Time Estimate: ${project.fold(0)(p => p.backlog.map(_.timeEstimate).sum)}, Real Spent Time: $projectTime")
        project = None
        tasksDone = Seq()
        reportTo.get ! ProjectDone
        context.become(receive)
      }

    case Project(title,backlog) => sender ! ImBusy

    case Terminated(a) =>
      log.debug(s"Recover $a")
      router = router.removeRoutee(a)
      val r = context.actorOf(Props[SoftwareEngineer])
      context watch r
      router = router.addRoutee(r)
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
  val leaders = mutable.Seq(system.actorOf(Props(new TeamLeader(5)), "Leader"))

  // Create the board inbox
  val board = Inbox.create(system)

  //Create sample projects
  val project = Project("simple project 1",Seq(Task(1,"1","1",100),Task(2,"2","2",1440),Task(3,"3","3",1330),Task(4,"4","4",1100),Task(5,"5","5",700)))
  val project2 = Project("simple project 2",Seq(Task(1,"1","1",1200),Task(2,"2","2",600),Task(3,"3","3",3000),Task(4,"4","4",6000),Task(5,"5","5",1000)))

  //send a project to a leader
  board.send(leaders.head,project)

  println(board.receive(1.second) match {
    case ImOnIt => "Leader, this estimate is very pessimistic!!"
    case _ => "Should change team"
  })

  board.send(leaders.head,project)
  board.receive(1.second) match {
    case ImOnIt => println("No need to contract more :)")
    case ImBusy => println("Should contract a leader with a smaller team!")
      leaders.:+(system.actorOf(Props(new TeamLeader(3)), "Leader2"))
      board.send(leaders.tail.head,project2)
  }

  board.receive(30.second) match {
    case ProjectDone =>
  }

  board.receive(30.second) match {
    case ProjectDone => system.terminate()
  }

  //evaluate the best leader and dismiss the other

  Await.result(system.whenTerminated, Duration.Inf)

}
