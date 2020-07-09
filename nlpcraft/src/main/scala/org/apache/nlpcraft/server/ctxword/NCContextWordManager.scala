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

package org.apache.nlpcraft.server.ctxword

import java.net.ConnectException
import java.util
import java.util.{List ⇒ JList}

import com.google.gson.reflect.TypeToken
import com.google.gson.{Gson, GsonBuilder}
import io.opencensus.trace.Span
import org.apache.http.HttpResponse
import org.apache.http.client.ResponseHandler
import org.apache.http.client.methods.{HttpGet, HttpPost}
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.HttpClients
import org.apache.http.util.EntityUtils
import org.apache.ignite.IgniteCache
import org.apache.nlpcraft.common.ascii.NCAsciiTable
import org.apache.nlpcraft.common.config.NCConfigurable
import org.apache.nlpcraft.common.nlp.core.NCNlpCoreManager
import org.apache.nlpcraft.common.{NCE, NCService}
import org.apache.nlpcraft.server.ignite.NCIgniteInstance
import org.apache.nlpcraft.server.mdo.{NCContextWordConfigMdo, NCContextWordFactors, NCExampleMdo}
import org.apache.nlpcraft.server.nlp.core.{NCNlpParser, NCNlpServerManager, NCNlpWord}
import org.apache.nlpcraft.server.opencensus.NCOpenCensusServerStats
import org.jibx.schema.codegen.extend.DefaultNameConverter

import scala.collection.JavaConverters._
import scala.collection.Map
import scala.util.control.Exception.catching

/**
  *
  */
object NCContextWordManager extends NCService with NCOpenCensusServerStats with NCIgniteInstance {
    private final val CTX_WORDS_LIMIT = 1000

    private final val CFG_DEFAULTS = Map(
        "min.element.total.score" → 1.0,
        "min.element.percent" → 0.5,
        "min.sentence.total.score" → 0.5,
        "min.sentence.ftext.score" → 0.5,
        "min.example.total.score" → 0.8,
        "min.example.ftext.score" → 0.5
    )

    private object Config extends NCConfigurable {
        lazy val url: Option[String] = getStringOpt("nlpcraft.server.ctxword.url")
    }

    private final val GSON = new Gson()
    private final val TYPE_RESP = new TypeToken[JList[JList[Suggestion]]]() {}.getType
    private final val CLIENT = HttpClients.createDefault

    private final val CONVERTER = new DefaultNameConverter
    private final val POS_PLURALS = Set("NNS", "NNPS")

    @volatile private var url: Option[String] = _
    @volatile private var parser: NCNlpParser = _
    @volatile private var cache: IgniteCache[NCContextWordRequest, Seq[NCContextWordResponse]] = _

    // We don't use directly bert and ftext indexes.
    case class Suggestion(word: String, score: Double, bert_score: Double, ftext_score: Double)
    case class RestSentence(text: String, indexes: JList[Int])
    case class RestRequest(
        sentences: JList[RestSentence],
        limit: Int,
        min_score: Double,
        min_ftext: Double,
        min_bert: Double
    )

    // We suppose that all configured values are NN or NNP (noun, singular form)
    private case class Word(text: String, index: Int, examplePos: String)

    private final val HANDLER: ResponseHandler[Seq[Seq[Suggestion]]] =
        (resp: HttpResponse) ⇒ {
            val code = resp.getStatusLine.getStatusCode
            val e = resp.getEntity

            val js = if (e != null) EntityUtils.toString(e) else null

            if (js == null)
                throw new RuntimeException(s"Unexpected empty response [code=$code]")

            code match {
                case 200 ⇒
                    val data: JList[JList[Suggestion]] = GSON.fromJson(js, TYPE_RESP)

                    // Skips header.
                    data.asScala.map(p ⇒ if (p.isEmpty) Seq.empty else p.asScala.tail)

                case 400 ⇒ throw new RuntimeException(js)
                case _ ⇒ throw new RuntimeException(s"Unexpected response [code=$code, response=$js]")
            }
        }

    @throws[NCE]
    def suggest(reqs: Seq[NCContextWordRequest], f: NCContextWordParameter): Seq[Seq[NCContextWordResponse]] = {
        require(url.isDefined)

        val res = scala.collection.mutable.LinkedHashMap.empty[NCContextWordRequest, Seq[NCContextWordResponse]] ++
            reqs.map(key ⇒ key → cache.get(key))

        val reqsSrv = res.filter { case (_, cachedWords) ⇒ cachedWords == null }.keys.toSeq

        if (reqsSrv.nonEmpty) {
            val reqsSrvNorm: Seq[(Seq[String], RestSentence)] =
                reqsSrv.groupBy(_.words).
                map {
                    case (words, seq) ⇒ words → RestSentence(words.mkString(" "), seq.map(_.wordIndex).sorted.asJava)
                }.toSeq

            require(reqsSrv.size == reqsSrvNorm.map(_._2.indexes.size).sum)

            val restReq =
                RestRequest(
                    sentences = reqsSrvNorm.map { case (_, restSen) ⇒ restSen }.asJava,
                    limit = f.limit,
                    min_score = f.totalScore,
                    min_ftext = f.ftextScore,
                    min_bert = f.bertScore
                )

            val post = new HttpPost(url.get)

            post.setHeader("Content-Type", "application/json")
            post.setEntity(new StringEntity(GSON.toJson(restReq), "UTF-8"))

            val resp: Seq[Seq[NCContextWordResponse]] =
                try
                    CLIENT.execute(post, HANDLER).
                        map(_.map(p ⇒
                            NCContextWordResponse(
                                word = p.word,
                                stem = NCNlpCoreManager.stemWord(p.word),
                                totalScore = p.score,
                                bertScore = p.bert_score,
                                ftextScore = p.ftext_score
                            ))
                        )
                finally
                    post.releaseConnection()

            require(reqsSrv.size == resp.size)

            val reqsSrvDenorm =
                reqsSrvNorm.flatMap { case (txt, sen) ⇒ sen.indexes.asScala.map(NCContextWordRequest(txt, _)) }

            def sort(seq: Seq[NCContextWordRequest]): Seq[NCContextWordRequest] =
                seq.sortBy(p ⇒ (p.words.mkString(" "), p.wordIndex))

            require(sort(reqsSrvDenorm) == sort(reqsSrv))

            resp.
                zip(reqsSrvDenorm).
                sortBy { case (_, req) ⇒
                    val i = reqsSrv.indexOf(req)

                    require(i >= 0)

                    i
                }.
                foreach { case (seq, req) ⇒
                    cache.put(req, seq)
                    res.update(req, seq)
                }
        }

        logger.whenInfoEnabled({
            logger.info(s"Request executed: \n${
                new GsonBuilder().
                    setPrettyPrinting().
                    create.
                    toJson(
                        res.map { case (req, resp) ⇒ (req.words.mkString(" "), req.wordIndex) → resp.asJava } .asJava
                    )
            }")
        })

        res.values.toSeq
    }

    override def start(parent: Span = null): NCService = startScopedSpan("start", parent) { _ ⇒
        catching(wrapIE) {
            cache = ignite.cache[NCContextWordRequest, Seq[NCContextWordResponse]]("ctxword-cache")
        }

        parser = NCNlpServerManager.getParser
        url = Config.url match {
            case Some(u) ⇒
                // It doesn't even check return code, just catch connection exception.
                try
                    HttpClients.createDefault.execute(new HttpGet(u))
                catch {
                    case e: ConnectException ⇒ throw new NCE(s"Service is not available: $u", e)
                }

                Some(if (u.last == '/') s"${u}suggestions" else s"$u/suggestions")
            case None ⇒ None
        }

        super.start()
    }

    override def stop(parent: Span = null): Unit = startScopedSpan("stop", parent) { _ ⇒
        cache = null

        super.stop()
    }

    private def substitute(template: Seq[String], substs: Iterable[Word]): Seq[String] = {
        require(substs.map(_.index).forall(i ⇒ i >= 0 && i < template.length))

        val substMap = substs.map(p ⇒ p.index → p).toMap

        template.zipWithIndex.map {  case (templ, i) ⇒
            substMap.get(i) match {
                case Some(subst) ⇒
                    if (POS_PLURALS.contains(subst.examplePos)) CONVERTER.pluralize(subst.text) else subst.text
                case None ⇒
                    templ
            }
        }
    }

    private def mkElementsFactors(
        meta: Map[String, AnyRef], defaults: Map[String, Double], elementsIds: Set[String]
    ): Map[String, Map[String, Double]] = {
        val allDefs = elementsIds.map(id ⇒ id → defaults).toMap

        meta.get("ctx.words.factors") match {
            case Some(v) ⇒
                v.asInstanceOf[util.HashMap[String, util.Map[String, Double]]].asScala.
                    map(p ⇒ p._1 → p._2.asScala).
                    flatMap { case (elemId, elemMeta) ⇒
                        allDefs.get(elemId) match {
                            case Some(elemDflts) ⇒
                                val unexp = elemDflts.keySet.diff(elemMeta.keySet)

                                if (unexp.nonEmpty)
                                    logger.warn(s"Unexpected factors: {$unexp} of element ID: '$elemId'.")

                                Some(elemId → (elemDflts ++ elemMeta))
                            case None ⇒
                                logger.warn(s"Unexpected element ID: '$elemId' data skipped.")

                                None
                        }
                    }.toMap

            case None ⇒ allDefs
        }
    }

    @throws[NCE]
    def makeConfig(
        mdlId: String,
        ctxSyns: Map[String /*Element ID*/, Map[String /*Value*/, Map[String /*Synonym stem*/, String /*Value*/]]],
        examples: Set[String],
        modelMeta: Map[String, AnyRef]
    ): NCContextWordConfigMdo = {
        val syns = ctxSyns.map { case (elemId, map) ⇒ elemId → map.flatMap { case (value, syns) ⇒ syns.values.map(_ → value) } }

        val exCfg = collection.mutable.HashMap.empty[String, Seq[NCExampleMdo]]
        val normExs: Set[Seq[NCNlpWord]] = examples.map(parser.parse(_))

        case class Holder(request: NCContextWordRequest, elementId: String)

        val allReqs: Seq[Holder] =
            // ctxSyns.map { case (elemId, map) ⇒ elemId → map.values.flatten.toSet }.flatMap { case (elemId, allElemSyns) ⇒
            ctxSyns.map { case (elemId, m) ⇒ elemId → (m.values.flatMap(_.keySet).toSeq, m.values.flatMap(_.values).toSeq) }.
                flatMap { case (elemId, (texts, stems)) ⇒
                    val elemNormExs: Map[Seq[NCNlpWord], Map[Int, String]] =
                        normExs.flatMap(e ⇒ {
                            val indexes = e.zipWithIndex.flatMap {
                                case (w, idx) ⇒ if (stems.contains(w.stem)) Some(idx → w.pos) else None
                            }.toMap

                            if (indexes.nonEmpty) Some(e → indexes) else None
                        }).toMap

                    if (elemNormExs.isEmpty)
                        throw new NCE(s"Examples not found for element: $elemId")

                    exCfg += elemId → elemNormExs.map { case (ex, substs) ⇒ NCExampleMdo(ex.map(_.word), substs) }.toSeq

                    elemNormExs.flatMap { case (ex, substs) ⇒
                        val exWords = ex.map(_.word)

                        texts.combinations(substs.size).flatMap(comb ⇒ {
                            require(comb.size == substs.size)

                            val words = substitute(exWords, substs.zip(comb).map { case ((idx, pos), w) ⇒ Word(w, idx, pos) })

                            substs.keys.map(NCContextWordRequest(words, _))
                        })
                    }.toSeq.map(req ⇒ Holder(req, elemId))
            }.toSeq

        val elemFs = mkElementsFactors(modelMeta, CFG_DEFAULTS, ctxSyns.keySet.toSet)
        val fs = NCContextWordFactors(elemFs, elemFs.head._2.keySet.map(name ⇒ name → elemFs.map(p ⇒ p._2(name)).min).toMap)

        val allResp = suggest(
            allReqs.map(_.request),
            NCContextWordParameter(limit = CTX_WORDS_LIMIT, totalScore = fs.getMin("min.element.total.score"))
        )

        require(allResp.size == allReqs.size)

        case class Group(word: NCContextWordResponse, count: Int)
        case class GroupFactor(group: Group, percent: Double)

        val groups =
            allReqs.zip(allResp).groupBy { case (h, _) ⇒ h.elementId }.map { case (elemId, seq) ⇒
                val cnt = exCfg(elemId).size.toDouble

                val suggs = seq.
                    flatMap { case (_, seq) ⇒ seq }.
                    filter(_.totalScore >= fs.get(elemId, "min.element.total.score")).
                    groupBy(_.stem).
                    // TODO: > 100 percent.
                    map { case (_, g) ⇒ GroupFactor(Group(g.minBy(-_.totalScore), g.size), g.size / cnt) }.
                    toSeq.
                    filter(_.percent >= fs.get(elemId, "min.element.percent"))

                if (suggs.isEmpty)
                    throw new NCE(s"Context words cannot be prepared for element: '$elemId'")

                elemId → suggs
            }

        logger.whenInfoEnabled({
            val tblWords = NCAsciiTable()

            def elem2Str(elemId: String): String = s"Element ID: '$elemId'"

            tblWords #= ("Element", "Context word", "ContextWord score", "Count", "Percent")

            groups.foreach { case (elemId, elemGroups) ⇒
                tblWords += (elem2Str(elemId), "", "", "", "")

                def f(d: Double): String = "%1.3f" format d

                elemGroups.
                    sortBy(p ⇒ (-p.percent, -p.group.word.totalScore)).
                    foreach(g ⇒ tblWords += ("", g.group.word.word, f(g.group.word.totalScore), g.group.count, f(g.percent)))
            }

            tblWords.info(logger, Some(s"Context words for model: $mdlId"))

            val tblExamles = NCAsciiTable()

            tblExamles #= ("Element", "Example (text, substitutions positions and POSes)")

            exCfg.foreach { case (elemId, examples) ⇒
                tblExamles += (elem2Str(elemId), "")

                examples.foreach(e ⇒ {
                    val txt = e.words.mkString(" ")
                    val substs =
                        e.substitutions.
                            toSeq.
                            sortBy { case (idx, _) ⇒ idx }.
                            map { case (idx, pos) ⇒ s"$idx($pos)" }.
                            mkString(", ")

                    tblExamles += ("", s"$txt { $substs }")
                })
            }

            tblExamles.info(logger, Some(s"Examples for model: $mdlId"))

            val sortedFactors = fs.minimalFactors.keys.toSeq.sorted

            val tblFs = NCAsciiTable()

            tblFs #= ("Element", "Factor name", "Factor value")

            for ((elemId, m) ← fs.elementsFactors) {
                tblFs += (elem2Str(elemId), "", "")

                for (f ← sortedFactors)
                    tblFs += ("", f, m(f))
            }

            tblFs += ("All elements minimal values", "", "")

            tblFs.info(logger, Some(s"Elements factors"))
        })

        NCContextWordConfigMdo(
            syns,
            groups.map { case (elemId, seq) ⇒ elemId → seq.map(_.group.word.stem).toSet },
            exCfg.toMap,
            exCfg.values.flatten.flatMap(_.substitutions.map { case (_, pos) ⇒ pos } ).toSet,
            fs
        )
    }
}