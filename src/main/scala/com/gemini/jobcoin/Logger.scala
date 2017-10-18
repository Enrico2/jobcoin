package com.gemini.jobcoin

import com.twitter.util.Memoize

object Logger {
  private[this] val mem = Memoize[String, Logger] { cat => new Logger(cat) }

  def get(cat: String) = mem(cat)
}

class Logger(cat: String) {
  def info(str: String): Unit = println(s"$cat: $str")
}