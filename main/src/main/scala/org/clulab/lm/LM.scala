package org.clulab.lm

import java.io.PrintWriter

import edu.cmu.dynet.Expression

/** Trait for language model (LM) functionality */
trait LM {
  def mkEmbeddings(words: Iterable[String], predPosition: Option[Int], doDropout:Boolean): Iterable[Expression]

  def dimensions: Int

  def saveX2i(printWriter: PrintWriter)
}


