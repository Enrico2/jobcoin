package com.gemini.jobcoin

import com.twitter.finagle.http.Status

case class RequestFailedException(status: Status, message: String)
  extends Exception(s"Request failed, code: ${status.code}; $message")

case class ParseException(json: String) extends Exception(s"Parsing JSON failed: $json")

case class UnexpectedJSONFormat(json: String) extends Exception(s"Unexpected JSON format: $json")

case class InsufficientFundsException(transaction: Transaction) extends Exception(s"insufficient funds to perform $transaction")
