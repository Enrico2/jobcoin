package com.gemini.jobcoin

import java.nio.charset.StandardCharsets
import com.twitter.finagle.{Http, http}
import com.twitter.io.Buf
import com.twitter.io.Buf.Utf8
import com.twitter.util.Await

object CLI extends App {

  private[this] val Host = "localhost"
  private[this] val client = Http.newService(Host + ":8084")

  val help =
    """
      |Available commands:
      |  - help                                       print this help.
      |  - register [address]*                        register one or more addresses. Get a dropbox address back
      |  - send [fromAddress] [toAddress] [amount]    Send Jobcoins
      |  - balance [address]                          Get balance
      |  - reset                                      Move all coins back to OGAccount
      |  - quit                                       Quits, duh. ;)
      |""".stripMargin

  println("---- Welcome to the Jobcoin app by Ran Magen ----")
  println(help)

  print("> ")
  var in = scala.io.StdIn.readLine().trim

  while (in.toLowerCase() != "quit") {
    if (in.isEmpty) {
      println("You gotta type something though... Try again.")
    } else if (in.toLowerCase() == "help") {
      println(help)
    } else {
      val req = http.Request(http.Method.Post, "/")
      req.content = Utf8.apply(in)
      req.host = Host
      val response = Await.result(client(req))

      val content = if (!response.content.isEmpty) {
        Buf.decodeString(response.content, StandardCharsets.UTF_8)
      } else ""

      response.status match {
        case http.Status.Ok =>
          if (content.nonEmpty) println(content) else println("Success.")

        case status =>
          println(s"request failed. Status: $status, body: $content")
      }
    }

    println()
    print("> ")
    in = scala.io.StdIn.readLine()
  }

  println("Wow, that was great code there. Goodbye!")
}
