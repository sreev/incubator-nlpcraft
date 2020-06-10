/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.nlpcraft.model.tools.suggestions

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{CopyOnWriteArrayList, CountDownLatch, TimeUnit}

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.apache.http.HttpResponse
import org.apache.http.client.ResponseHandler
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.HttpClients
import org.apache.http.util.EntityUtils
import org.apache.nlpcraft.common.ascii.NCAsciiTable
import org.apache.nlpcraft.common.makro.NCMacroParser
import org.apache.nlpcraft.common.nlp.core.NCNlpPorterStemmer
import org.apache.nlpcraft.common.util.NCUtils
import org.apache.nlpcraft.common.version.NCVersion
import org.apache.nlpcraft.model.NCModelFileAdapter

import scala.collection.JavaConverters._
import scala.collection._

case class ParametersHolder(
    modelPath: String,
    url: String,
    limit: Int,
    minScore: Double,
    synonymsWords: Int,
    debug: Boolean
)

object NCSuggestionsGeneratorImpl {
    /**
      * Suggestion data holder.
      *
      * @param word Word
      * @param bert Bert factor.
      * @param normalized Normalized bert factor.
      * @param ftext FText factor.
      * @param `ftext-sentence` TODO:
      * @param score Calculated summary factor: normalized * weight1 + ftext * weight2 (weights values are 1 currently)
      */
    case class Suggestion(
        word: String, bert: Double, normalized: Double, ftext: Double, `ftext-sentence`: Double, score: Double
    )

    case class RequestData(sentence: String, example: String, elementId: String, lower: Int, upper: Int, limit: Int)
    case class RestRequest(sentence: String, simple: Boolean, lower: Int, upper: Int, limit: Int)
    case class RestResponse(data: java.util.ArrayList[Suggestion])

    private final val GSON = new Gson
    private final val TYPE_RESP = new TypeToken[RestResponse]() {}.getType
    private final val SEPARATORS = Seq('?', ',', '.', '-', '!')

    private def mkHandler(req: RequestData): ResponseHandler[Seq[Suggestion]] =
        (resp: HttpResponse) ⇒ {
            val code = resp.getStatusLine.getStatusCode
            val e = resp.getEntity

            val js = if (e != null) EntityUtils.toString(e) else null

            if (js == null)
                throw new RuntimeException(s"Unexpected empty response [req=$req, code=$code]")

            code match {
                case 200 ⇒
                    val data: RestResponse = GSON.fromJson(js, TYPE_RESP)

                    data.data.asScala

                case 400 ⇒ throw new RuntimeException(js)
                case _ ⇒ throw new RuntimeException(s"Unexpected response [req=$req, code=$code, response=$js]")
            }
        }

    private def split(s: String): Seq[String] = s.split(" ").toSeq.map(_.trim).filter(_.nonEmpty)
    private def toStem(s: String): String = split(s).map(NCNlpPorterStemmer.stem).mkString(" ")
    private def toStemWord(s: String): String = NCNlpPorterStemmer.stem(s)

    private def getAllSlices(seq1: Seq[String], seq2: Seq[String]): Seq[Int] = {
        val seq = mutable.Buffer.empty[Int]

        var i = seq1.indexOfSlice(seq2)

        while (i >= 0) {
            seq += i

            i = seq1.indexOfSlice(seq2, i + 1)
        }

        seq
    }

    def process(data: ParametersHolder): Unit = {
        val mdl = new NCModelFileAdapter(data.modelPath) {}

        val parser = new NCMacroParser()

        if (mdl.getMacros != null)
            mdl.getMacros.asScala.foreach { case (name, str) ⇒ parser.addMacro(name, str) }

        val client = HttpClients.createDefault

        case class Word(word: String, stem: String) {
            require(!word.contains(" "), s"Word cannot contains spaces: $word")
            require(
                word.forall(ch ⇒
                    ch.isLetterOrDigit ||
                        ch == '\'' ||
                        SEPARATORS.contains(ch)
                ),
                s"Unsupported symbols: $word"
            )
        }

        val examples =
            mdl.getExamples.asScala.
                map(ex ⇒ SEPARATORS.foldLeft(ex)((s, ch) ⇒ s.replaceAll(s"\\$ch", s" $ch "))).
                map(ex ⇒ {
                    val seq = ex.split(" ")

                    seq → seq.map(toStemWord)
                }).
                toMap

        val elemSyns =
            mdl.getElements.asScala.map(e ⇒ e.getId → e.getSynonyms.asScala.flatMap(parser.expand)).
                map { case (id, seq) ⇒ id → seq.map(txt ⇒ split(txt).map(p ⇒ Word(p, toStemWord(p)))) }.toMap

        val allReqs =
            elemSyns.map {
                case (elemId, syns) ⇒
                    val normSyns: Seq[Seq[Word]] = syns.filter(_.size <= data.synonymsWords)
                    val synsStems = normSyns.map(_.map(_.stem))
                    val synsWords = normSyns.map(_.map(_.word))

                    val reqs =
                        examples.flatMap { case (exampleWords, exampleStems) ⇒
                            val exampleIdxs = synsStems.flatMap(synStems ⇒ getAllSlices(exampleStems, synStems))

                            def mkRequestData(idx: Int, synStems: Seq[String], synStemsIdx: Int): RequestData = {
                                val fromIncl = idx
                                val toExcl = idx + synStems.length

                                RequestData(
                                    sentence = exampleWords.zipWithIndex.flatMap {
                                        case (exampleWord, i) ⇒
                                            i match {
                                                case x if x == fromIncl ⇒ synsWords(synStemsIdx)
                                                case x if x > fromIncl && x < toExcl ⇒ Seq.empty
                                                case _ ⇒ Seq(exampleWord)
                                            }
                                    }.mkString(" "),
                                    example = exampleWords.mkString(" "),
                                    elementId = elemId,
                                    lower = idx,
                                    upper = idx + synStems.length - 1,
                                    limit = data.limit
                                )
                            }

                            (for (idx ← exampleIdxs; (synStems, i) ← synsStems.zipWithIndex) yield mkRequestData(idx, synStems, i)).
                                distinct
                        }

                    elemId → reqs.toSet
            }.filter(_._2.nonEmpty)

        println(s"Examples count: ${examples.size}")
        println(s"Synonyms count: ${elemSyns.map(_._2.size).sum}")
        println(s"Request prepared: ${allReqs.map(_._2.size).sum}")

        val allSuggs = new java.util.concurrent.ConcurrentHashMap[String, java.util.List[Suggestion]]()
        val cdl = new CountDownLatch(allReqs.map { case (_, seq) ⇒ seq.size }.sum)
        val debugs = mutable.HashMap.empty[RequestData, Seq[Suggestion]]
        val cnt = new AtomicInteger(0)

        for ((elemId, reqs) ← allReqs; req ← reqs) {
            NCUtils.asFuture(
                _ ⇒ {
                    val post = new HttpPost(data.url)

                    post.setHeader("Content-Type", "application/json")
                    post.setEntity(
                        new StringEntity(
                            GSON.toJson(
                                RestRequest(
                                    sentence = req.sentence,
                                    simple = false,
                                    lower = req.lower,
                                    upper = req.upper,
                                    limit = req.limit
                                )
                            ),
                            "UTF-8"
                        )
                    )

                    val resp: Seq[Suggestion] =
                        try
                            client.execute(post, mkHandler(req))
                        finally
                            post.releaseConnection()

                    if (data.debug)
                        debugs += req → resp

                    val i = cnt.incrementAndGet()

                    if (i % 10 == 0)
                        println(s"Executed: $i requests.")

                    allSuggs.
                        computeIfAbsent(elemId, (_: String) ⇒ new CopyOnWriteArrayList[Suggestion]()).
                        addAll(resp.asJava)
                },
                (e: Throwable) ⇒ {
                    e.printStackTrace()

                    cdl.countDown()
                },
                (_: Boolean) ⇒ cdl.countDown()
            )
        }

        cdl.await(Long.MaxValue, TimeUnit.MILLISECONDS)

        println("All requests executed.")

        val allSynsStems = elemSyns.flatMap(_._2).flatten.map(_.stem).toSet

        val filteredSuggs =
            allSuggs.asScala.map {
                case (elemId, elemSuggs) ⇒ elemId → elemSuggs.asScala.filter(_.score >= data.minScore)
            }.filter(_._2.nonEmpty)

        val avgScores = filteredSuggs.map { case (elemId, suggs) ⇒ elemId → (suggs.map(_.score).sum / suggs.size) }
        val counts = filteredSuggs.map { case (elemId, suggs) ⇒ elemId → suggs.size }

        val tbl = NCAsciiTable()

        tbl #= (
            "Element",
            "Suggestion",
            "Summary factor",
            "Count",
            "Bert/Ftext score",
            "Bert",
            "Bert norm",
            "Ftext",
            "Ftext-Sentence"
        )

        filteredSuggs.
            foreach { case (elemId, elemSuggs) ⇒
                val seq: Seq[(Suggestion, Int)] = elemSuggs.
                    map(sugg ⇒ (sugg, toStem(sugg.word))).
                    groupBy { case (_, stem) ⇒ stem }.
                    filter { case (stem, _) ⇒ !allSynsStems.contains(stem) }.
                    map { case (_, group) ⇒
                        val seq = group.map { case (sugg, _) ⇒ sugg }.sortBy(-_.score)

                        // Drops repeated.
                        (seq.head, seq.length)
                    }.
                    toSeq

                val normFactor = seq.map(_._2).sum.toDouble / seq.size / avgScores(elemId)

                seq.
                    map { case (sugg, cnt) ⇒ (sugg, cnt, sugg.score * normFactor * cnt.toDouble / counts(elemId)) }.
                    sortBy { case (_, _, cumFactor) ⇒ -cumFactor }.
                    zipWithIndex.
                    foreach { case ((sugg, cnt, cumFactor), sugIdx) ⇒
                        def f(d: Double): String = "%1.3f" format d

                        tbl += (
                            if (sugIdx == 0) elemId else " ",
                            sugg.word,
                            f(cumFactor),
                            cnt,
                            f(sugg.score),
                            f(sugg.bert),
                            f(sugg.normalized),
                            f(sugg.ftext),
                            f(sugg.`ftext-sentence`)
                        )
                    }
            }

        if (data.debug) {
            var i = 1

            debugs.groupBy(_._1.example).foreach { case (_, m) ⇒
                m.toSeq.sortBy(_._1.sentence).foreach { case (req, suggs) ⇒
                    val s =
                        split(req.sentence).zipWithIndex.map { case (w, i) ⇒
                            i match {
                                case x if x == req.lower && x == req.upper ⇒ s"<<<$w>>>"
                                case x if x == req.lower ⇒ s"<<<$w"
                                case x if x == req.upper ⇒ s"$w>>>"
                                case _ ⇒ w
                            }
                        }.mkString(" ")

                    println(
                        s"$i. " +
                            s"Request=$s, " +
                            s"suggestions=[${suggs.map(_.word).mkString(", ")}], " +
                            s"element=${req.elementId}"
                    )

                    i = i + 1
                }
            }
        }

        println("Suggestions:")

        tbl.render()
    }
}

object NCSuggestionsGenerator extends App {
    private lazy val DFLT_URL: String = "http://localhost:5000/synonyms"
    private lazy val DFLT_LIMIT: Int = 10 // TODO: add scoreLimit
    private lazy val DFLT_MIN_SCORE: Double = 0
    private lazy val DFLT_SYNONYMNS_WORDS: Int = 1
    private lazy val DFLT_DEBUG: Boolean = false

    /**
      *
      * @param msg Optional error message.
      */
    private def errorExit(msg: String = null): Unit = {
        if (msg != null)
            System.err.println(
                s"""
                   |ERROR:
                   |    $msg""".stripMargin
            )

        if (msg == null)
            System.err.println(
                s"""
                   |NAME:
                   |    NCSuggestionsGenerator -- NLPCraft synonyms suggestions generator for given model.
                   |
                   |SYNOPSIS:
                   |    java -cp apache-nlpcraft-incubating-${NCVersion.getCurrent}-all-deps.jar org.apache.nlpcraft.model.tools.suggestions.NCSuggestionsGenerator [PARAMETERS]
                   |
                   |DESCRIPTION:
                   |    This utility generates synonyms suggestions for given NLPCraft model.
                   |    Note that Python NLP server should be started and accessible parameter URL.
                   |
                   |    This Java class can be run from the command line or from an IDE like any other
                   |    Java application.""".stripMargin
            )

        System.err.println(
            s"""
               |PARAMETERS:
               |    [--model|-m] model path
               |        Mandatory file model path.
               |        It should have one of the following extensions: .js, .json, .yml, or .yaml
               |
               |    [--url|-u] url
               |        Optional Python NLP server URL.
               |        Default is $DFLT_URL.
               |
               |    [--limit|-l] limit
               |        Optional maximum suggestions per synonyms count value.
               |        Default is $DFLT_LIMIT.
               |
               |    [--score|-c] score
               |        Optional minimal suggestion score value.
               |        Default is $DFLT_MIN_SCORE.
               |
               |    [--syns|-s] synonyms count
               |        Optional words count which defined which synonyms words count supported.
               |        Default is $DFLT_SYNONYMNS_WORDS.
               |
               |    [--debug|-d] [true|false]
               |        Optional flag on whether or not to debug output.
               |        Default is $DFLT_DEBUG.
               |
               |    [--help|-h|-?]
               |        Prints this usage information.
               |
               |EXAMPLES:
               |    java -cp apache-nlpcraft-incubating-${NCVersion.getCurrent}-all-deps.jar org.apache.nlpcraft.model.tools.sqlgen.NCSqlModelGenerator
               |        -m src/main/scala/org/apache/nlpcraft/examples/weather/weather_model.json
               |        -u $DFLT_URL
            """.stripMargin
        )

        System.exit(1)
    }

    /**
      *
      * @param v
      * @param name
      */
    private def mandatoryParam(v: String, name: String): Unit =
        if (v == null)
            throw new IllegalArgumentException(s"Parameter is mandatory and must be set: $name")

    /**
      *
      * @param v
      * @param name
      * @return
      */
    private def parseNum[T](v: String, name: String, extract: String ⇒ T, fromIncl: T, toIncl: T)(implicit e: T ⇒ Number): T = {
        val t =
            try
                extract(v.toLowerCase)
            catch {
                case _: NumberFormatException ⇒ throw new IllegalArgumentException(s"Invalid numeric: $name")
            }

        val td = t.doubleValue()

        if (td < fromIncl.doubleValue() || td > toIncl.doubleValue())
            throw new IllegalArgumentException(s"Invalid `$name` range. Must be between: $fromIncl and $toIncl")

        t
    }

    /**
      *
      * @param v
      * @param name
      * @return
      */
    private def parseBoolean(v: String, name: String): Boolean =
        v.toLowerCase match {
            case "true" ⇒ true
            case "false" ⇒ false

            case _ ⇒ throw new IllegalArgumentException(s"Invalid boolean value in: $name $v")
        }

    /**
      *
      * @param cmdArgs
      * @return
      */
    private def parseCmdParameters(cmdArgs: Array[String]): ParametersHolder = {
        if (cmdArgs.isEmpty || !cmdArgs.intersect(Seq("--help", "-h", "-help", "--?", "-?", "/?", "/help")).isEmpty)
            errorExit()

        var mdlPath: String = null

        var url = DFLT_URL
        var limit = DFLT_LIMIT
        var minScore = DFLT_MIN_SCORE
        var synsWords = DFLT_SYNONYMNS_WORDS
        var debug = DFLT_DEBUG

        var i = 0

        try {
            while (i < cmdArgs.length - 1) {
                val k = cmdArgs(i).toLowerCase
                val v = cmdArgs(i + 1)

                k match {
                    case "--model" | "-m" ⇒ mdlPath = v
                    case "--url" | "-u" ⇒ url = v
                    case "--limit" | "-l" ⇒ limit = parseNum(v, k, (s: String) ⇒ s.toInt, 1, Integer.MAX_VALUE)
                    case "--score" | "-c" ⇒ minScore = parseNum(v, k, (s: String) ⇒ s.toDouble, 0, Integer.MAX_VALUE)
                    case "--syns" | "-s" ⇒ synsWords = parseNum(v, k, (s: String) ⇒ s.toInt, 1, Integer.MAX_VALUE)
                    case "--debug" | "-d" ⇒ debug = parseBoolean(v, k)

                    case _ ⇒ throw new IllegalArgumentException(s"Invalid argument: ${cmdArgs(i)}")
                }

                i = i + 2
            }

            mandatoryParam(mdlPath, "--model")
        }
        catch {
            case e: Exception ⇒ errorExit(e.getMessage)
        }

        ParametersHolder(mdlPath, url, limit, minScore, synsWords, debug)
    }

    NCSuggestionsGeneratorImpl.process(parseCmdParameters(args))
}