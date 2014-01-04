import com.amazonaws.auth.PropertiesCredentials
import com.amazonaws.services.ec2.{AmazonEC2Client => Ec2}
import com.amazonaws.regions.Region
import com.amazonaws.regions.Regions
import com.amazonaws.services.ec2.model.DescribeInstancesRequest
import com.amazonaws.services.ec2.model.Filter
import scala.collection.JavaConversions._
import com.amazonaws.services.opsworks.model.StartInstanceRequest
import com.amazonaws.services.ec2.model.StartInstancesRequest
import com.amazonaws.services.ec2.model.StopInstancesRequest
import com.amazonaws.services.ec2.model.Instance
import com.amazonaws.services.route53.AmazonRoute53Client
import com.amazonaws.services.route53.model.ListResourceRecordSetsRequest
import com.amazonaws.services.route53.model.Change
import com.amazonaws.services.route53.model.ChangeAction
import com.amazonaws.services.route53.model.ResourceRecordSet
import com.amazonaws.services.route53.model.RRType
import com.amazonaws.services.route53.model.ChangeResourceRecordSetsRequest
import com.amazonaws.services.route53.model.ChangeBatch
import com.amazonaws.services.route53.model.ResourceRecord
import com.typesafe.config.ConfigFactory
import com.amazonaws.auth.BasicAWSCredentials

object VPNControl {
  
  def vpnInstanceProp(instanceTag: String, f: Instance => String)(implicit client: Ec2): Option[String] = {
    val insReq = new DescribeInstancesRequest().withFilters(new Filter().withName("tag:Name").withValues(instanceTag))
    val insRes = client.describeInstances(insReq)
    insRes.getReservations.flatMap(_.getInstances()).map(f).headOption
  }
  
  def vpnInstanceId(instanceTag: String)(implicit client: Ec2): Option[String] = vpnInstanceProp(instanceTag, _.getInstanceId)
  
  def vpnDNS(instanceTag: String)(implicit client: Ec2): String = {
    Thread.sleep(5000)
    vpnInstanceProp(instanceTag, _.getPublicDnsName).map { dns =>
      println("Empty DNS. Reschedule check.")
      if (dns.isEmpty()) {
        vpnDNS(instanceTag)
      } else dns
    } getOrElse "Couldn't find VPN instance"
  }
    
  
  def start(instanceTag: String)(implicit client: Ec2): String = {
    println("Starting VPN server")
    vpnInstanceId(instanceTag).map { id =>
	    val startReq = new StartInstancesRequest().withInstanceIds(id)
	    val startRes = client.startInstances(startReq)
	    val state = startRes.getStartingInstances().map(_.getCurrentState()).head
	    s"VPN instance started. State '$state'"
    } getOrElse "Couldn't find VPN instance to start"
  }
  
  def stop(instanceTag: String)(implicit client: Ec2): String = {
	println("Stopping VPN server")
	vpnInstanceId(instanceTag).map { id => 
	  val stopReq = new StopInstancesRequest().withInstanceIds(id)
	  val stopRes = client.stopInstances(stopReq)
	  val state = stopRes.getStoppingInstances().map(_.getCurrentState()).head
	  s"VPN instance stopped. State '$state'"
	} getOrElse "Couldn't find VPN instance"
  }
  
  def updateVPNRecord(zoneID: String, recordName: String, newDNS: String)(implicit client: AmazonRoute53Client) = {
    val listReq = new ListResourceRecordSetsRequest(zoneID).withMaxItems("1").withStartRecordName(recordName)
    val listRes = client.listResourceRecordSets(listReq)
    val oldRset = listRes.getResourceRecordSets()
    
    val changes = new ChangeBatch()
    //first remove all old records
    oldRset.foreach { rset =>
      changes.withChanges(new Change(ChangeAction.DELETE, rset))
    }
    
    //create a new record containing the public dns of the started ec2 instance
    val rset = new ResourceRecordSet(recordName, RRType.CNAME).withTTL(60).withResourceRecords(new ResourceRecord(newDNS))
    val changereq = new ChangeResourceRecordSetsRequest(zoneID, changes.withChanges(new Change(ChangeAction.CREATE, rset)))
    
    client.changeResourceRecordSets(changereq)
    
    s"Updated vpn subdomain to $newDNS" 
  }
  
  def main(args: Array[String]): Unit = {
    val config = ConfigFactory.load()
    val cred = new BasicAWSCredentials(config.getString("accessKey"), config.getString("secretKey"))
    val tag = config.getString("instanceTag")
    val zoneID = config.getString("zoneID")
    val recordName = config.getString("recordName")
	
	implicit val ec2 = new Ec2(cred)
	ec2.setRegion(Region.getRegion(Regions.EU_WEST_1))
	
	implicit val r53 = new AmazonRoute53Client(cred)
	r53.setRegion(Region.getRegion(Regions.EU_WEST_1))
	
    if (args.length == 0 || (args(0) != "start" && args(0) != "stop"))
		println("Please use start | stop as argument")	
	else if (args(0) == "start") {
		println(start(tag))
		println(updateVPNRecord(zoneID, recordName, vpnDNS(tag)))
	} else if (args(0) == "stop")
	    println(stop(tag))
	      
  }
}
