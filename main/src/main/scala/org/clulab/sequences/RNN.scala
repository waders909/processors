package org.clulab.sequences

import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.{FileWriter, PrintWriter}

import org.clulab.embeddings.word2vec.Word2Vec
import org.clulab.struct.Counter
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.mutable.{ArrayBuffer, ListBuffer}
import edu.cmu.dynet._
import edu.cmu.dynet.Expression._
import RNN._
import org.clulab.utils.{MathUtils, Serializer}

import scala.collection.mutable
import scala.io.Source
import scala.util.Random

/**
  * Implements the biLSTM-CRF of Lample et al. (2016)
  * @author Mihai
  */
class RNN {
  var model:RNNParameters = _

  /**
    * Trains on the given training sentences, and report accuracy after each epoch on development sentences
    * @param trainSentences Training sentences
    * @param devSentences Development/validation sentences, used for logging purposes only
    */
  def train(trainSentences: Array[Array[Row]], devSentences:Array[Array[Row]]): Unit = {
    //val trainer = new SimpleSGDTrainer(model.parameters, learningRate = 0.01f)
    val trainer = new RMSPropTrainer(model.parameters)
    var cummulativeLoss = 0.0
    var numTagged = 0
    var sentCount = 0
    var sentences = trainSentences
    val rand = new Random(RANDOM_SEED)

    for(epoch <- 0 until EPOCHS) {
      sentences = MathUtils.randomize(sentences, rand)

      logger.info(s"Started epoch $epoch.")
      for(sentence <- sentences) {
        sentCount += 1
        ComputationGraph.renew()

        // predict tag emission scores for one sentence, from the biLSTM hidden states
        val words = sentence.map(_.get(0))
        val emissionScores = emissionScoresAsExpressions(words,  doDropout = DO_DROPOUT)
        val transitionMatrix = new ExpressionVector
        for(i <- 0 until model.t2i.size) {
          transitionMatrix.add(lookup(model.T, i))
        }

        // get the gold tags for this sentence
        val goldTagIds = toTagIds(sentence.map(_.get(1)))

        // compute loss for this sentence
        val loss = sentenceLoss(emissionScores, transitionMatrix, goldTagIds)

        cummulativeLoss += loss.value().toFloat
        numTagged += sentence.length

        if(sentCount % 1000 == 0) {
          logger.info("Cummulative loss: " + cummulativeLoss / numTagged)
          cummulativeLoss = 0.0
          numTagged = 0
        }

        // backprop
        ComputationGraph.backward(loss)
        trainer.update()
      }

      evaluate(devSentences, epoch)
    }
  }

  /** Computes the score of the given sequence of tags (tagSeq) */
  def sentenceScore(emissionScoresForSeq:ExpressionVector, // Dim: sentenceSize x tagCount
                    transitionMatrix:ExpressionVector, // Dim: tagCount x tagCount
                    tagCount:Int,
                    tagSeq:Array[Int],
                    startTag:Int,
                    stopTag:Int): Expression = {
    // start with the transition score to first tag from START
    var score = pick2D(transitionMatrix, tagSeq.head, startTag)

    for(i <- tagSeq.indices) {
      if(i > 0) {
        // transition score from the previous tag
        score = score + pick2D(transitionMatrix, tagSeq(i), tagSeq(i - 1))
      }

      // emission score for the current tag
      score = score + pick(emissionScoresForSeq(i), tagSeq(i))
    }

    // conclude with the transition score to STOP from last tag
    score = score + pick2D(transitionMatrix, stopTag, tagSeq.last)

    score
  }

  /** Picks the scalar element from an expression that is a matrix */
  def pick2D(matrix:ExpressionVector, row:Int, column:Int): Expression = {
    pick(matrix(row), column)
  }

  /**
    * Implements the forward algorithm to compute the partition score for this lattice
    * This code inspired by this PyTorch implementation: https://pytorch.org/tutorials/beginner/nlp/advanced_tutorial.html
    */
  def mkPartitionScore(emissionScoresForSeq:ExpressionVector, // Dim: sentenceSize x tagCount
                       transitionMatrix:ExpressionVector,
                       startTag:Int, stopTag:Int): Expression = { // Dim: tagCount x tagCount
    val tagCount = transitionMatrix.size

    // sum of scores of reaching each tag at this time step
    var forward = new ExpressionVector()
    for(t <- 0 until tagCount) {
      //
      // cost (in log space) of starting at a given tag
      // the only possible starting tag is START; all others are disabled
      //
      val alphaAtT0:Float = if(t == startTag) 0 else LOG_MIN_VALUE
      forward.add(input(alphaAtT0))
    }

    for(t <- emissionScoresForSeq.indices) {
      val alphasAtT = new ExpressionVector()
      val emitScores = emissionScoresForSeq(t)

      for(nextTag <- 0 until tagCount) {
        val alphasForTag = new ExpressionVector()
        val emitScore = pick(emitScores, nextTag) // scalar: emision score for nextTag

        for(srcTag <- 0 until tagCount) {
          val transScore = pick2D(transitionMatrix, nextTag, srcTag) // scalar: transition score to nextTag from srcTag
          val alphaToTagFromSrc =
            forward(srcTag) +
            transScore +
            emitScore

          alphasForTag.add(alphaToTagFromSrc)
        }

        alphasAtT.add(logSumExp(alphasForTag))
      }

      forward = alphasAtT
    }

    val terminalVars = new ExpressionVector()
    for(t <- 0 until tagCount) {
      terminalVars.add(forward(t) + pick2D(transitionMatrix, stopTag, t))
    }

    val total = logSumExp(terminalVars)
    total
  }

  /**
    * Objective function that maximizes the CRF probability of the gold sequence of tags
    * @param emissionScoresForSeq emission scores for the whole sequence, and all tags
    * @param transitionMatrix transition matrix between all tags
    * @param golds gold sequence of tags
    * @return the negative prob of the gold sequence (in log space)
    */
  def sentenceLoss(emissionScoresForSeq:ExpressionVector, // Dim: sentenceSize x tagCount
                   transitionMatrix:ExpressionVector, // Dim: tagCount x tagCount
                   golds:Array[Int]): Expression = { // Dim: sentenceSize
    val startTag = model.t2i(START_TAG)
    val stopTag = model.t2i(STOP_TAG)

    val scoreOfGoldSeq =
      sentenceScore(emissionScoresForSeq, transitionMatrix, model.t2i.size, golds, startTag, stopTag)

    val partitionScore =
      mkPartitionScore(emissionScoresForSeq, transitionMatrix, startTag, stopTag)

    partitionScore - scoreOfGoldSeq
  }

  /** Greedy loss function, ignoring transition scores (not used) */
  def sentenceLossGreedy(emissionScoresForSeq:Array[Expression], // Dim: sentenceSize x tagCount
                         transitionMatrix:Expression, // Dim: tagCount x tagCount
                         golds:Array[Int]): Expression = { // Dim: sentenceSize

    val goldLosses = new ExpressionVector()
    assert(emissionScoresForSeq.length == golds.length)

    for(i <- emissionScoresForSeq.indices) {
      // gold tag for word at position i
      val goldTid = golds(i)
      // emissionScoresForSeq(i) = all tag emission scores for the word at position i
      goldLosses.add(pickNegLogSoftmax(emissionScoresForSeq(i), goldTid))
    }

    sum(goldLosses)
  }

  def toTagIds(tags: Array[String]):Array[Int] = {
    val ids = new ArrayBuffer[Int]()
    for(tag <- tags) {
      ids += model.t2i(tag)
    }
    ids.toArray
  }

  def printCoNLLOutput(pw:PrintWriter, words:Array[String], golds:Array[String], preds:Array[String]): Unit = {
    for(i <- words.indices) {
      pw.println(words(i) + " " + golds(i) + " " + preds(i))
    }
    pw.println()
  }

  def accuracy(golds:Array[String], preds:Array[String]): (Int, Int) = {
    assert(golds.length == preds.length)
    var correct = 0
    for(e <- preds.zip(golds)) {
      if(e._1 == e._2) {
        correct += 1
      }
    }
    (golds.length, correct)
  }

  /** Logs accuracy score on devSentences; also saves the output in the file dev.output.<EPOCH> */
  def evaluate(devSentences:Array[Array[Row]], epoch:Int): Unit = {
    var total = 0
    var correct = 0

    val pw = new PrintWriter(new FileWriter("dev.output." + epoch))
    logger.debug("Started evaluation on the evaluation dataset...")
    for(sent <- devSentences) {
      val words = sent.map(_.get(0))
      val golds = sent.map(_.get(1))

      // println("PREDICT ON SENT: " + words.mkString(", "))
      val preds = predict(words)
      val (t, c) = accuracy(golds, preds)
      total += t
      correct += c

      printCoNLLOutput(pw, words, golds, preds)
    }

    pw.close()
    logger.info(s"Accuracy on ${devSentences.length} dev sentences: " + correct.toDouble / total)
  }

  /**
    * Generates tag emission scores for the words in this sequence, stored as Expressions
    * @param words One training or testing sentence
    */
  def emissionScoresAsExpressions(words: Array[String], doDropout:Boolean): ExpressionVector = {
    val embeddings = words.map(mkEmbedding)

    val fwStates = transduce(embeddings, model.fwRnnBuilder)
    val bwStates = transduce(embeddings.reverse, model.bwRnnBuilder).toArray.reverse
    assert(fwStates.size == bwStates.length)
    val states = concatenateStates(fwStates, bwStates).toArray
    assert(states.length == words.length)

    val H = parameter(model.H)
    val O = parameter(model.O)

    val emissionScores = new ExpressionVector()
    for(s <- states) {
      var l1 = Expression.tanh(H * s)
      if(doDropout) {
        l1 = Expression.dropout(l1, DROPOUT_PROB)
      }
      emissionScores.add(O * l1)
    }

    emissionScores
  }

  def emissionScoresToArrays(expressions:Iterable[Expression]): Array[Array[Float]] = {
    val lattice = new ArrayBuffer[Array[Float]]()
    for(expression <- expressions) {
      val probs = expression.value().toVector().toArray
      lattice += probs
    }
    lattice.toArray
  }

  def transitionMatrixToArrays(trans: LookupParameter, size: Int): Array[Array[Float]] = {
    val transitionMatrix = new ArrayBuffer[Array[Float]]()
    for(i <- 0 until size) {
      transitionMatrix += lookup(trans, i).value().toVector().toArray
    }
    transitionMatrix.toArray
  }

  def printTagScores(header:String, scores:Array[Float]): Unit = {
    print(header)
    for(j <- scores.indices) {
      val tag = model.i2t(j)
      print(s" {$tag, ${scores(j)}}")
    }
    println()
  }

  def viterbi(emissionScores: Array[Array[Float]], transitionMatrix: Array[Array[Float]]): Array[Int] = {

    // initial scores in log space
    val initScores = new Array[Float](model.t2i.size)
    for(i <- initScores.indices) initScores(i) = LOG_MIN_VALUE
    initScores(model.t2i(START_TAG)) = 0

    // the best overall scores at time step -1 (start)
    var forwardVar = initScores

    // backpointers for the entire lattice
    val backPointers = new ArrayBuffer[Array[Int]]()

    // iterate over all the words in this sentence
    for(t <- emissionScores.indices) {
      // scores for *all* tags for time step t
      val scoresAtT = new Array[Float](emissionScores(t).length)

      // backpointers for this time step
      val backPointersAtT = new Array[Int](emissionScores(t).length)

      // iterate over all possible tags for this time step
      for(nextTag <- emissionScores(t).indices) {

        // compute the score of transitioning into this tag from *any* previous tag
        val transitionIntoNextTag = ArrayMath.sum(forwardVar, transitionMatrix(nextTag))

        //printTagScores(s"\tforwardVar + transitionMatrix:", transitionIntoNextTag)

        // this previous tag has the best transition score into nextTag
        val bestPrevTag = ArrayMath.argmax(transitionIntoNextTag)
        // keep track of the best backpointer for nextTag
        backPointersAtT(nextTag) = bestPrevTag

        // this is the best *transition* score into nextTag
        scoresAtT(nextTag) = transitionIntoNextTag(bestPrevTag)
      }

      // these are the best overall scores at time step t = transition + emission + previous
      // note that the emission scores are the same for a given nextTag, so it's Ok to do this outside of the above loop
      forwardVar = ArrayMath.sum(scoresAtT, emissionScores(t))

      // keep track of the backpointers at this time step
      backPointers += backPointersAtT
    }

    assert(emissionScores.length == backPointers.length)

    // transition into the stop tag
    forwardVar = ArrayMath.sum(forwardVar, transitionMatrix(model.t2i(STOP_TAG)))
    var bestLastTag = ArrayMath.argmax(forwardVar)
    val pathScore = forwardVar(bestLastTag)

    // best path in the lattice, in reverse order
    val bestPathReversed = new ListBuffer[Int]
    bestPathReversed += bestLastTag
    for(backPointersAtT <- backPointers.reverse) {
      bestLastTag = backPointersAtT(bestLastTag)
      bestPathReversed += bestLastTag
    }
    assert(bestPathReversed.last == model.t2i(START_TAG))
    val bestPath = bestPathReversed.slice(0, bestPathReversed.size - 1).reverse.toArray

    bestPath
  }

  /** Runs a greedy algorithm to generate the sequence of tag ids, ignoring transition scores (not used) */
  def greedyPredict(lattice:Array[Array[Float]]):Array[Int] = {
    val tagIds = new ArrayBuffer[Int]()
    for(probs <- lattice) {
      var max = Float.MinValue
      var tid = -1
      for(i <- probs.indices) {
        if(probs(i) > max) {
          max = probs(i)
          tid = i
        }
      }
      assert(tid > -1)
      tagIds += tid
    }
    tagIds.toArray
  }

  /**
    * Predict the sequence tags that applies to the given sequence of words
    * @param words The input words
    * @return The predicted sequence of tags
    */
  def predict(words:Array[String]):Array[String] = synchronized {
    // Note: this block MUST be synchronized. Currently the computational graph in DyNet is a static variable.
    val emissionScores:Array[Array[Float]] = synchronized {
      emissionScoresToArrays(emissionScoresAsExpressions(words, doDropout = false)) // these scores do not have softmax
    }

    // Note: this block probably does not need to be synchronized as it is not using the computational graph. TODO: check
    val transitionMatrix:Array[Array[Float]] = synchronized {
      transitionMatrixToArrays(model.T, model.t2i.size)
    }

    val tagIds = viterbi(emissionScores, transitionMatrix)
    val tags = new ArrayBuffer[String]()
    for(tid <- tagIds) tags += model.i2t(tid)
    tags.toArray
  }

  def concatenateStates(l1: Iterable[Expression], l2: Iterable[Expression]): Iterable[Expression] = {
    val c = new ArrayBuffer[Expression]()
    for(e <- l1.zip(l2)) {
      c += concatenate(e._1, e._2)
    }
    c
  }

  def mkEmbedding(word: String):Expression = {
    //
    // make sure you preprocess the word similarly to the embedding library used!
    //   GloVe large does not do any preprocessing
    //   GloVe small lowers the case
    //   Our Word2Vec uses Word2Vec.sanitizeWord
    //
    val sanitized = word.toLowerCase() // Word2Vec.sanitizeWord(word)

    val wordEmbedding =
      if(model.w2i.contains(sanitized))
      // found the word in the known vocabulary
        lookup(model.lookupParameters, model.w2i(sanitized))
      else {
        // not found; return the embedding at position 0, which is reserved for unknown words
        lookup(model.lookupParameters, 0)
      }

    // biLSTM over character embeddings
    val charEmbedding =
      mkCharacterEmbedding(word)

    concatenate(wordEmbedding, charEmbedding)
  }

  def mkCharacterEmbedding(word: String): Expression = {
    //println(s"make embedding for word [$word]")
    val charEmbeddings = new ArrayBuffer[Expression]()
    for(i <- word.indices) {
      if(model.c2i.contains(word.charAt(i)))
        charEmbeddings += lookup(model.charLookupParameters, model.c2i(word.charAt(i)))
    }
    val fwOut = transduce(charEmbeddings, model.charFwRnnBuilder).last
    val bwOut = transduce(charEmbeddings.reverse, model.charBwRnnBuilder).last
    concatenate(fwOut, bwOut)
  }

  def transduce(embeddings:Iterable[Expression], builder:RnnBuilder): Iterable[Expression] = {
    builder.newGraph()
    builder.startNewSequence()
    val states = embeddings.map(builder.addInput)
    states
  }

  def initialize(trainSentences:Array[Array[Row]], embeddingsFile:String): Unit = {
    val (w2i, t2i, c2i) = mkVocabs(trainSentences)
    logger.debug(s"Tag vocabulary has ${t2i.size} entries.")
    logger.debug(s"Word vocabulary has ${w2i.size} entries (including 1 for unknown).")
    logger.debug(s"Character vocabulary has ${c2i.size} entries.")

    logger.debug("Initializing DyNet...")
    Initialize.initialize(Map("random-seed" -> RANDOM_SEED))
    model = mkParams(w2i, t2i, c2i)
    model.initialize(embeddingsFile) // Only necessary if constructing from scratch by training
    logger.debug("Completed initialization.")
  }
}

class RNNParameters(
  var w2i:Map[String, Int],
  val t2i:Map[String, Int],
  val i2t:Array[String],
  val c2i:Map[Char, Int],
  val parameters:ParameterCollection,
  var lookupParameters:LookupParameter,
  val fwRnnBuilder:RnnBuilder,
  val bwRnnBuilder:RnnBuilder,
  val H:Parameter,
  val O:Parameter,
  val T:LookupParameter, // transition matrix for Viterbi; T[i][j] = transition *to* i *from* j
  val charLookupParameters:LookupParameter,
  val charFwRnnBuilder:RnnBuilder,
  val charBwRnnBuilder:RnnBuilder) {

  protected def toFloatArray(doubles: Array[Double]): Array[Float] = {
    val floats = new Array[Float](doubles.length)
    for (i <- doubles.indices) {
      floats(i) = doubles(i).toFloat
    }
    floats
  }


  protected def add(dst:Array[Double], src:Array[Double]): Unit = {
    assert(dst.length == src.length)
    for(i <- dst.indices) {
      dst(i) += src(i)
    }
  }

  def initialize(embeddingsFile: String): Unit = {
    initializeEmbeddings(embeddingsFile)
    initializeTransitions()
  }

  def initializeTransitions(): Unit = {
    val startTag = t2i(START_TAG)
    val stopTag = t2i(STOP_TAG)

    for (i <- 0 until t2i.size) {
      T.initialize(i, initTransitionsTo(i, t2i.size, startTag, stopTag))
    }
  }

  def initTransitionsTo(dst: Int, size:Int, startTag: Int, stopTag: Int): FloatVector = {
    val transScores = new Array[Float](size)

    for(i <- 0 until size) {
      transScores(i) = randomNormal(Dim(1)).value().toFloat() / size // pseudo Glorot
    }

    if(RNN.USE_DOMAIN_CONSTRAINTS) {
      // discourage transitions to START from anything
      if (dst == startTag) {
        for (i <- 0 until size)
          transScores(i) = LOG_MIN_VALUE
      } else {
        // discourage transitions to anything from STOP
        transScores(stopTag) = LOG_MIN_VALUE

        // discourage transitions to I-X from B-Y or I-Y
        val dstTag = i2t(dst)
        if (dstTag.startsWith("I-")) {
          for (i <- 0 until size) {
            val srcTag = i2t(i)
            if ((srcTag.startsWith("B-") || srcTag.startsWith("I-")) &&
              srcTag.substring(2) != dstTag.substring(2)) {
              transScores(i) = LOG_MIN_VALUE
            }
          }
        }
      }
    }

    new FloatVector(transScores)
  }

  def initializeEmbeddings(embeddingsFile: String): Unit = {
    logger.debug(s"Loading embeddings from file $embeddingsFile...")
    val w2v = new Word2Vec(embeddingsFile) // Some(w2i.keySet))

    logger.debug(s"Loaded embeddings for a vocabulary of ${w2v.matrix.size} words.")
    lookupParameters = parameters.addLookupParameters(w2v.matrix.size + 1, Dim(EMBEDDING_SIZE)) // + 1 for the unk embedding

    val commonWords = new ListBuffer[String]
    commonWords += UNK_WORD
    for (word <- w2v.matrix.keySet) {
      commonWords += word
    }
    w2i = commonWords.sorted.zipWithIndex.toMap

    for(word <- w2v.matrix.keySet){// w2i.keySet) {
      lookupParameters.initialize(w2i(word), new FloatVector(toFloatArray(w2v.matrix(word))))
    }
    logger.debug(s"Loaded ${w2v.matrix.size} embeddings.")
  }

  def printTransitionMatrix(): Unit = {
    val tagCount = t2i.size
    for(dstTag <- 0 until tagCount) {
      println("Transitions TO tag " + i2t(dstTag) + ":")
      val transScores = lookup(T, dstTag).value().toVector()
      for(srcTag <- 0 until tagCount) {
        println("\tFROM " + i2t(srcTag) + ": " + transScores(srcTag))
      }
    }
  }
}

object RNN {
  val logger:Logger = LoggerFactory.getLogger(classOf[RNN])

  val EPOCHS = 2
  val RANDOM_SEED = 2522620396l // used for both DyNet, and the JVM seed for shuffling data
  val DROPOUT_PROB = 0.1f
  val DO_DROPOUT = true
  val EMBEDDING_SIZE = 200
  val RNN_STATE_SIZE = 100
  val NONLINEAR_SIZE = 32
  val RNN_LAYERS = 1
  val CHAR_RNN_LAYERS = 1
  val CHAR_EMBEDDING_SIZE = 32
  val CHAR_RNN_STATE_SIZE = 16

  val UNK_WORD = "<UNK>"
  val START_TAG = "<START>"
  val STOP_TAG = "<STOP>"

  val LOG_MIN_VALUE:Float = -10000

  // case features
  val CASE_x = 0
  val CASE_X = 1
  val CASE_Xx = 2
  val CASE_xX = 3
  val CASE_n = 4
  val CASE_o = 5

  val USE_DOMAIN_CONSTRAINTS = true

  def casing(w:String): Int = {
    if(w.charAt(0).isLetter) { // probably an actual word
      // count upper and lower-case chars
      var uppers = 0
      for(j <- 0 until w.length) {
        if(w.charAt(j).isUpper) {
          uppers += 1
        }
      }

      var v = CASE_x
      if (uppers == w.length) v = CASE_X
      else if (uppers == 1 && w.charAt(0).isUpper) v = CASE_Xx
      else if (uppers >= 1 && !w.charAt(0).isUpper) v = CASE_xX
      v
    } else if(isNumber(w))
      CASE_n
    else
      CASE_o
  }

  def isNumber(w:String): Boolean = {
    for(i <- 0 until w.length) {
      val c = w.charAt(i)
      if(! c.isDigit && c != '-' && c != '.' && c != ',')
        return false
    }
    true
  }

  protected def save[T](printWriter: PrintWriter, map: Map[T, Int]): Unit = {
    map.foreach { case (key, value) =>
      printWriter.println(s"$key\t$value")
    }
    printWriter.println() // Separator
  }

  def save(dynetFilename:String, x2iFilename: String, rnnParameters: RNNParameters):Unit = {
    val modelSaver = new ModelSaver(dynetFilename)
    modelSaver.addModel(rnnParameters.parameters, "/all")
    modelSaver.done()

    Serializer.using(new PrintWriter(new OutputStreamWriter(new BufferedOutputStream(new FileOutputStream(x2iFilename)), "UTF-8"))) { printWriter =>
      save(printWriter, rnnParameters.w2i)
      save(printWriter, rnnParameters.t2i)
      save(printWriter, rnnParameters.c2i)
    }
  }

  def load(filename: String, keyValueBuilders: Array[KeyValueBuilder]): Unit = {
    var index = 0

    Serializer.using(Source.fromFile(filename, "UTF-8")) { source =>
      source.getLines.foreach { line =>
        if (line.nonEmpty) {
          val Array(key, value) = line.split('\t')
          keyValueBuilders(index).add(key, value)
        }
        else
          index += 1
      }
    }
  }

  trait KeyValueBuilder {
    def add(key: String, value: String): Unit
  }

  class MapBuilder[KeyType](val converter: String => KeyType) extends KeyValueBuilder {
    val mutableMap: mutable.Map[KeyType, Int] = new mutable.HashMap

    def add(key: String, value: String): Unit = mutableMap += ((converter(key), value.toInt))

    def toMap: Map[KeyType, Int] = mutableMap.toMap
  }

  def load(dynetFilename:String, x2iFilename: String):RNNParameters = {
    def stringToString(string: String): String = string
    def stringToChar(string: String): Char = string.charAt(0)

    val w2iBuilder = new MapBuilder(stringToString)
    val t2iBuilder = new MapBuilder(stringToString)
    val c2iBuilder = new MapBuilder(stringToChar)
    val builders: Array[KeyValueBuilder] = Array(w2iBuilder, t2iBuilder, c2iBuilder)

    load(x2iFilename, builders)

    val model = mkParams(w2iBuilder.toMap, t2iBuilder.toMap, c2iBuilder.toMap)

    new ModelLoader(dynetFilename).populateModel(model.parameters, "/all")
    model
  }

  def fromIndexToString(s2i: Map[String, Int]):Array[String] = {
    var max = Int.MinValue
    for(v <- s2i.values) {
      if(v > max) {
        max = v
      }
    }
    assert(max > 0)
    val i2s = new Array[String](max + 1)
    for(k <- s2i.keySet) {
      i2s(s2i(k)) = k
    }
    i2s
  }

  def mkVocabs(trainSentences:Array[Array[Row]]): (Map[String, Int], Map[String, Int], Map[Char, Int]) = {
    val words = new Counter[String]()
    val tags = new Counter[String]()
    val chars = new mutable.HashSet[Char]()
    for(sentence <- trainSentences) {
      for(token <- sentence) {
        val word = token.get(0)
        words += word // Word2Vec.sanitizeWord(word)
        for(i <- word.indices) {
          chars += word.charAt(i)
        }
        tags += token.get(1)
      }
    }

    val commonWords = new ListBuffer[String]
    commonWords += UNK_WORD // the word at position 0 is reserved for unknown words
    for(w <- words.keySet) {
      if(words.getCount(w) > 1) {
        commonWords += w
      }
    }

    tags += START_TAG
    tags += STOP_TAG

    val w2i = commonWords.sorted.zipWithIndex.toMap // These must be sorted for consistency across runs
    val t2i = tags.keySet.toList.sorted.zipWithIndex.toMap
    val c2i = chars.toList.sorted.zipWithIndex.toMap

    (w2i, t2i, c2i)
  }

  /**
    * Initializes the transition matrix for a tagset of size size
    * T[i, j] stores a transition *to* i *from* j
    */
  def mkTransitionMatrix(parameters:ParameterCollection, t2i:Map[String, Int], i2t:Array[String]): LookupParameter = {
    val size = t2i.size
    val rows = parameters.addLookupParameters(size, Dim(size))
    rows
  }

  def mkParams(w2i:Map[String, Int], t2i:Map[String, Int], c2i:Map[Char, Int]): RNNParameters = {
    val parameters = new ParameterCollection()
    // val lookupParameters = parameters.addLookupParameters(w2i.size, Dim(EMBEDDING_SIZE))
    val embeddingSize = EMBEDDING_SIZE + 2 * CHAR_RNN_STATE_SIZE // + CASE_o + 1
    val fwBuilder = new LstmBuilder(RNN_LAYERS, embeddingSize, RNN_STATE_SIZE, parameters)
    val bwBuilder = new LstmBuilder(RNN_LAYERS, embeddingSize, RNN_STATE_SIZE, parameters)
    val H = parameters.addParameters(Dim(NONLINEAR_SIZE, 2 * RNN_STATE_SIZE))
    val O = parameters.addParameters(Dim(t2i.size, NONLINEAR_SIZE)) // + CASE_o + 1))
    val i2t = fromIndexToString(t2i)
    val T = mkTransitionMatrix(parameters, t2i, i2t)
    logger.debug("Created parameters.")

    val charLookupParameters = parameters.addLookupParameters(c2i.size, Dim(CHAR_EMBEDDING_SIZE))
    val charFwBuilder = new LstmBuilder(CHAR_RNN_LAYERS, CHAR_EMBEDDING_SIZE, CHAR_RNN_STATE_SIZE, parameters)
    val charBwBuilder = new LstmBuilder(CHAR_RNN_LAYERS, CHAR_EMBEDDING_SIZE, CHAR_RNN_STATE_SIZE, parameters)

    new RNNParameters(w2i, t2i, i2t, c2i, parameters, null, fwBuilder, bwBuilder, H, O, T,
      charLookupParameters, charFwBuilder, charBwBuilder)
  }

  def main(args: Array[String]): Unit = {
    val trainFile = args(0)
    val devFile = args(1)
    val trainSentences = ColumnReader.readColumns(trainFile)
    val devSentences = ColumnReader.readColumns(devFile)
    val embeddingsFile = args(2)

    val rnn = new RNN()
    rnn.initialize(trainSentences, embeddingsFile)
    rnn.train(trainSentences, devSentences)

    val dynetFilename = "rnn.dat"
    val x2iFilename = "x2i.dat"

    save(dynetFilename, x2iFilename, rnn.model)

    val pretrainedRnn = new RNN()
    val rnnParameters = load(dynetFilename, x2iFilename)
    pretrainedRnn.model = rnnParameters

    rnn.evaluate(devSentences, -1)
    pretrainedRnn.evaluate(devSentences, -1)
  }
}

object ArrayMath {
  def argmax(vector:Array[Float]):Int = {
    var bestValue = Float.MinValue
    var bestArg = 0
    for(i <- vector.indices) {
      if(vector(i) > bestValue) {
        bestValue = vector(i)
        bestArg = i
      }
    }
    bestArg
  }

  def sum(v1:Array[Float], v2:Array[Float]): Array[Float] = {
    assert(v1.length == v2.length)
    val s = new Array[Float](v1.length)
    for(i <- v1.indices) {
      s(i) = v1(i) + v2(i)
    }
    s
  }
}