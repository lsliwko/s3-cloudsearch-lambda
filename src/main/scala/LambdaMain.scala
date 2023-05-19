import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.cloudsearchv2.AmazonCloudSearchClientBuilder
import com.amazonaws.services.cloudsearchv2.model.DescribeDomainsRequest
import com.amazonaws.services.cloudsearchdomain.AmazonCloudSearchDomainClientBuilder
import com.amazonaws.services.cloudsearchdomain.model.{ContentType, UploadDocumentsRequest}
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.S3EventNotificationRecord
import com.amazonaws.services.lambda.runtime.{Context, LambdaLogger, RequestHandler}
import com.amazonaws.services.lambda.runtime.events.{S3Event, SQSEvent}
import com.amazonaws.services.lambda.runtime.serialization.events.LambdaEventSerializers
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.util.IOUtils
import org.apache.commons.lang3.StringUtils
import com.google.common.base.CharMatcher
import com.google.common.io.ByteStreams
import com.google.gson.GsonBuilder

import java.io.ByteArrayInputStream
import scala.collection.parallel.CollectionConverters._
import scala.jdk.CollectionConverters._
import scala.util._

class LambdaMain extends RequestHandler[SQSEvent, Unit] {
  private var logger : LambdaLogger = _

  //env variables
  private lazy val region = readSystemEnv("AWS_REGION")
  private lazy val cloudSearchDomainName = readSystemEnv("CLOUDSEARCH_DOMAIN_NAME")

  private lazy val gson = new GsonBuilder().setPrettyPrinting().create()

  private lazy val s3Client = AmazonS3ClientBuilder.defaultClient()
  private lazy val s3EventSerializer = LambdaEventSerializers.serializerFor(
    classOf[S3Event],
    Thread.currentThread().getContextClassLoader()  //important
  )

  private lazy val cloudSearchClient = AmazonCloudSearchClientBuilder.defaultClient()
  private lazy val cloudSearchDomainClient = retry(5) {
    //find domain by name
    val describeDomainsResult = cloudSearchClient.describeDomains(
      new DescribeDomainsRequest().withDomainNames(cloudSearchDomainName)
    )

    val domainStatus =
      describeDomainsResult.getDomainStatusList.asScala.toList match {
        case List(domainStatus) => domainStatus
        case _ => throw new RuntimeException("cloud search domain missing")
      }

    //read document service endpoint and create client
    AmazonCloudSearchDomainClientBuilder.standard()
      .withEndpointConfiguration(new EndpointConfiguration(
        domainStatus.getDocService.getEndpoint,
        region
      )).build()
  }


  def handleRequest(input: SQSEvent, context: Context): Unit = {
    logger = context.getLogger() //set LambdaLogger from context

    val documentsBatch = input.getRecords.asScala.par //parallelism
      .flatMap { sqsMessage =>
        //deserialize S3 event from SQS event body
        val s3Event = s3EventSerializer.fromJson(sqsMessage.getBody)
        s3Event.getRecords.asScala //flatten S3EventNotificationRecord(s)
      }
      .collect {

        case record if record.getEventName.startsWith("ObjectCreated") =>
          val objectKey = normalizeObjectKey(record)
          logger.log(s"Created object: $objectKey\n")
          Try {
            readObjectContent(record)
          } match { //read object content (if available)
            case Failure(_) => logger.log(s"Content cannot be read: $objectKey\n"); None
            case Success(objectContent) =>
              //update CloudSearch document (with object content)
              Some(Map(
                "type" -> "add",
                "id" -> objectKey,
                "fields" -> Map("content" -> objectContent).asJava //index fields
              ).asJava)
          }

        case record if record.getEventName.startsWith("ObjectRemoved") =>
          val objectKey = normalizeObjectKey(record)
          logger.log(s"Deleted object: $objectKey\n")
          //delete CloudSearch document
          Some(Map(
            "type" -> "delete",
            "id" -> objectKey
          ).asJava)

      }
      .flatten.toArray.array

    //execute batch documents update
    updateCloudSearchDocuments(gson.toJson(documentsBatch))
  }
  
  private def updateCloudSearchDocuments(documentsBatch: String): Unit =
    retry(5) {
      logger.log(s"CloudSearch documents batch:\n${documentsBatch}\n")
      cloudSearchDomainClient.uploadDocuments(new UploadDocumentsRequest()
        .withContentType(ContentType.Applicationjson)
        .withDocuments(new ByteArrayInputStream(documentsBatch.getBytes))
        .withContentLength(documentsBatch.length)
      )
    }

  private def readObjectContent(record: S3EventNotificationRecord): String =
    retry(5) {
      val s3Object = s3Client.getObject(
        record.getS3.getBucket.getName,
        record.getS3.getObject.getUrlDecodedKey
      )
      //CloudSearch text field has 1MB limit (also cleanup)
      StringUtils.normalizeSpace(CharMatcher.ascii().retainFrom(
        IOUtils.toString(ByteStreams.limit(s3Object.getObjectContent, 1048576))
      ))
    }

  @inline
  private def normalizeObjectKey(record: S3EventNotificationRecord): String =
    StringUtils.normalizeSpace(CharMatcher.ascii().retainFrom(
      record.getS3.getObject.getUrlDecodedKey
    ))

  @inline
  private def readSystemEnv(env: String): String =
    Option(System.getenv(env)).getOrElse(throw new RuntimeException(s"${env} not set"))

  @annotation.tailrec
  private def retry[T](n: Int)(function: => T): T = {
    Try { function } match {
      case Success(x) => x
      case _ if n > 1 => retry(n - 1)(function)
      case Failure(e) => throw e
    }
  }

}