package com.gemini.jobcoin

import java.nio.charset.StandardCharsets
import com.twitter.finagle.{Http, Service, http}
import com.twitter.io.Buf
import com.twitter.util.{Future, Time, TimeFormat}
import scala.math.BigDecimal.RoundingMode
import scala.util.parsing.json.JSON

class JobcoinNetwork() {
  private[this] val JobcoinHost = "jobcoin.gemini.com"
  private[this] val client: Service[http.Request, http.Response] = Http.newService(JobcoinHost + ":80")
  private[this] val timeFormat = new TimeFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
  private[this] val log = Logger.get("JobcoinNetwork")

  private[this] def call[T](request: http.Request, f: http.Response => T): Future[T] = {
    request.host = JobcoinHost
    client(request).map(f)
  }

  private[this] def httpGet[T](path: String)(f: http.Response => T): Future[T] = {
    call(http.Request(http.Method.Get, path), f)
  }

  private[this] def httpPost[T](path: String, args: Map[String, String])(f: http.Response => T): Future[T] = {
    val params = args.map { case (name, value) => s"$name=$value" }.mkString("&")
    call(http.Request(http.Method.Post, s"$path?$params"), f)
  }

  /**
    * Create a new transaction using the Jobcoin API.
    *
    * @param transaction The transaction to create.
    * @return Successful Future[Unit] if the request returned 200 OK.
    */
  def createTransaction(transaction: Transaction): Future[Unit] = {
    log.info(s"Creating Transaction $transaction")
    val args = transaction.toArgs
    httpPost("/fool/api/transactions", args) { response =>
      response.status match {
        case http.Status.Ok => ()
        case http.Status.UnprocessableEntity => throw InsufficientFundsException(transaction)
        case status => throw RequestFailedException(status, s"POST /fool/api/transactions; args = ${args.mkString(",")}")
      }
    }
  }

  /**
    * Get all transactions from the Jobcoin API
    *
    * @return a Future of a list of Transactions.
    */
  def getTransactions(): Future[Seq[Transaction]] = {
    log.info("getting transactions from server...")
    httpGet("/fool/api/transactions") { response =>
      response.status match {
        case http.Status.Ok =>
          val json = Buf.decodeString(response.content, StandardCharsets.UTF_8)
          if (json.trim.isEmpty) {
            Nil
          } else {
            parseAsTransactions(json)
          }
        case status =>
          throw RequestFailedException(status, "GET /fool/api/transactions")
      }
    }
  }

  /**
    * Get the Jobcoin balance of an Address from the Jobcoin API.
    * N.B. only used as a utility in Reset.scala
    */
  def getAddressBalance(address: Address): Future[String] = {
    val path = s"/fool/api/addresses/$address"
    log.info(s"getting address balance for $address")
    httpGet(path) { response =>
      response.status match {
        case http.Status.Ok =>
          val json = Buf.decodeString(response.content, StandardCharsets.UTF_8)
          JSON.parseFull(json).get.asInstanceOf[Map[String, String]]("balance")
        case status =>
          throw RequestFailedException(status, path)
      }
    }
  }

  private[this] def parseAsTransactions(json: String): Seq[Transaction] = {
    JSON.parseFull(json) match {
      case None => throw ParseException(json)
      case Some(listOrMap) => listOrMap match {
        case _: Map[String, Any] => throw UnexpectedJSONFormat(json)

        case list: List[Map[String, String]] => list.map { lt =>
          Transaction(
            fromAddress = lt.get("fromAddress"),
            toAddress = lt("toAddress"),
            amount = BigDecimal(lt("amount")).setScale(8, RoundingMode.HALF_DOWN),
            amountString = lt.get("amount"),
            timestamp = timeFormat.parse(lt("timestamp"))
          )
        }
      }
    }
  }
}

case class Transaction(
  fromAddress: Option[Address],
  toAddress: Address,
  amount: BigDecimal, // To handle precision issues.
  amountString: Option[String] = None,
  timestamp: Time = Time.now
) {
  def this(fromAddress: Address, toAddress: Address, amount: BigDecimal) =
    this(Some(fromAddress), toAddress, amount)

  def toArgs: Map[String, String] = Map(
    "toAddress" -> toAddress,
    "amount" -> amountString.getOrElse(amount.toString)
  ) ++ fromAddress.map { from => Map("fromAddress" -> from) }.getOrElse(Map.empty)
}
