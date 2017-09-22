package org.clulab.utils

import java.io._
import java.nio.charset.Charset

import scala.collection.mutable.ListBuffer

/**
 * File utilities
 * User: mihais
 * Date: 1/9/14
 */
object Files {
  val TEMP_DIR_ATTEMPTS = 100

  def mkTmpDir(prefix:String, deleteOnExit:Boolean = true):String = {
    val baseDir = new File(System.getProperty("java.io.tmpdir"))

    // to minimize collisions, the dir name contains the time and the thread id
    val baseName = prefix + "-" + System.nanoTime().toString + "-" + Thread.currentThread().getId + "-"

    for(counter <- 0 until TEMP_DIR_ATTEMPTS) {
      val tempDir = new File(baseDir, baseName + counter.toString)
      if (tempDir.mkdir()) {
        if(deleteOnExit) tempDir.deleteOnExit()
        // println("work dir: " + tempDir.getAbsolutePath)
        return tempDir.getAbsolutePath
      }
    }

    throw new IllegalStateException("ERROR: Failed to create directory within "
      + TEMP_DIR_ATTEMPTS + " attempts (tried "
      + baseName + "0 to " + baseName + (TEMP_DIR_ATTEMPTS - 1) + ')')
  }

  val FILE_CHARSET: Charset = Charset.forName("ISO-8859-1")

  def toPrintWriter(w:Writer):PrintWriter = {
    w match {
      case pw:PrintWriter => pw
      case bw:BufferedWriter => new PrintWriter(bw)
      case _ => new PrintWriter(new BufferedWriter(w))
    }
  }

  def toBufferedReader(r:Reader):BufferedReader = {
    r match {
      case br:BufferedReader => br
      case _ => new BufferedReader(r)
    }
  }

  /** Recursively finds all files with the given extension in the given directory */
  def findFiles(dir:String, ext:String):List[File] = {
    val files = new ListBuffer[File]

    // find all files ending with ext in this directory
    val fileNameFilter = new FilenameFilter {
      override def accept(file: File, name: String): Boolean = {
        name.toLowerCase.endsWith("." + ext)
      }
    }
    files ++= new File(dir).listFiles(fileNameFilter).toList

    // recursive call
    val dirNameFilter = new FilenameFilter {
      override def accept(dir: File, name: String): Boolean = {
        val file = new File(dir.getAbsolutePath + File.separator + name)
        file.isDirectory
      }
    }
    val subdirs = new File(dir).listFiles(dirNameFilter)
    for(subdir <- subdirs) {
      files ++= findFiles(subdir.getAbsolutePath, ext)
    }

    files.toList
  }

  /**
    * Extracts a file stored inside a jar
    * @param jarFileName The name of the jar file
    * @param entryName The name of the file to be extracted
    * @param outFileName The extracted file will be saved here
    */
  def extractEntry(jarFileName:String, entryName:String, outFileName:String,
                   deleteOnExit:Boolean = true, bufSize:Int = 131072): Unit = {
    val jar = new java.util.jar.JarFile(jarFileName)
    val entry = jar.getEntry(entryName)
    val is = jar.getInputStream(entry)
    val fos = new FileOutputStream(outFileName)
    val buffer = new Array[Byte](bufSize)
    var done = false
    while(! done) {
      val num = is.read(buffer, 0, bufSize)
      if(num > 0) {
        fos.write(buffer, 0, num)
      } else {
        done = true
      }
    }
    fos.close()
    is.close()

    if(deleteOnExit)
      new File(outFileName).deleteOnExit()
  }
}
