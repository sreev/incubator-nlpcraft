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

package org.apache.nlpcraft.probe.mgrs.nlp

import java.io.Serializable
import java.util
import java.util.concurrent._
import java.util.function.Predicate
import java.util.{Date, Objects}

import io.opencensus.trace.{Span, Status}
import org.apache.nlpcraft.common.NCErrorCodes._
import org.apache.nlpcraft.common._
import org.apache.nlpcraft.common.ascii.NCAsciiTable
import org.apache.nlpcraft.common.config.NCConfigurable
import org.apache.nlpcraft.common.debug.NCLogHolder
import org.apache.nlpcraft.common.nlp.{NCNlpSentence, NCNlpSentenceNote}
import org.apache.nlpcraft.model._
import org.apache.nlpcraft.model.impl.NCTokenLogger
import org.apache.nlpcraft.model.intent.impl.NCIntentSolverInput
import org.apache.nlpcraft.model.opencensus.stats.NCOpenCensusModelStats
import org.apache.nlpcraft.model.tools.embedded.NCEmbeddedResult
import org.apache.nlpcraft.probe.mgrs.conn.NCConnectionManager
import org.apache.nlpcraft.probe.mgrs.conversation.NCConversationManager
import org.apache.nlpcraft.probe.mgrs.dialogflow.NCDialogFlowManager
import org.apache.nlpcraft.probe.mgrs.model.NCModelManager
import org.apache.nlpcraft.probe.mgrs.nlp.enrichers.dictionary.NCDictionaryEnricher
import org.apache.nlpcraft.probe.mgrs.nlp.enrichers.limit.NCLimitEnricher
import org.apache.nlpcraft.probe.mgrs.nlp.enrichers.model.NCModelEnricher
import org.apache.nlpcraft.probe.mgrs.nlp.enrichers.relation.NCRelationEnricher
import org.apache.nlpcraft.probe.mgrs.nlp.enrichers.sort.NCSortEnricher
import org.apache.nlpcraft.probe.mgrs.nlp.enrichers.stopword.NCStopWordEnricher
import org.apache.nlpcraft.probe.mgrs.nlp.enrichers.suspicious.NCSuspiciousNounsEnricher
import org.apache.nlpcraft.probe.mgrs.nlp.impl._
import org.apache.nlpcraft.probe.mgrs.nlp.validate._
import org.apache.nlpcraft.probe.mgrs.{NCProbeMessage, NCProbeVariants}

import scala.collection.JavaConverters._
import scala.collection.{Seq, _}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

/**
  * Probe enrichment manager.
  */
object NCProbeEnrichmentManager extends NCService with NCOpenCensusModelStats {
    private final val MAX_NESTED_TOKENS = 32

    // Embedded probe Java callback function.
    private type EMBEDDED_CB = java.util.function.Consumer[NCEmbeddedResult]

    private final val mux = new Object()

    @volatile private var embeddedCbs: mutable.Set[EMBEDDED_CB] = _
    @volatile private var pool: ExecutorService = _
    @volatile private var executor: ExecutionContextExecutor = _

    private object Config extends NCConfigurable {
        final private val pre = "nlpcraft.probe"

        def id: String = getString(s"$pre.id")
        def resultMaxSize: Int = getInt(s"$pre.resultMaxSizeBytes")

        def check(): Unit =
            if (resultMaxSize <= 0)
                throw new NCE(s"Configuration property value must be > 0 [" +
                    s"name=$pre.resultMaxSizeBytes, " +
                    s"value=$resultMaxSize" +
                s"]")
    }

    Config.check()

    /**
     *
     * @param parent Optional parent span.
     * @return
     */
    override def start(parent: Span = null): NCService = startScopedSpan("start", parent) { _ ⇒
        ackStarting()

        embeddedCbs = mutable.HashSet.empty[EMBEDDED_CB]

        pool = U.mkThreadPool("probe-enrichment")
        executor = ExecutionContext.fromExecutor(pool)

        ackStarted()
    }

    /**
     *
     * @param parent Optional parent span.
     */
    override def stop(parent: Span = null): Unit = startScopedSpan("stop", parent) { _ ⇒
        ackStopping()

        mux.synchronized {
            if (embeddedCbs != null)
                embeddedCbs.clear()
        }

        U.shutdownPools(pool)
        executor = null
        pool = null

        ackStopped()
    }

    /**
      *
      * @param cb Callback.
      */
    private[probe] def addEmbeddedCallback(cb: EMBEDDED_CB): Unit = {
        mux.synchronized {
            embeddedCbs.add(cb)
        }
    }

    /**
      *
      * @param cb Callback.
      */
    private[probe] def removeEmbeddedCallback(cb: EMBEDDED_CB): Unit = {
        mux.synchronized {
            embeddedCbs.remove(cb)
        }
    }

    /**
      * Processes 'ask' request from probe server.
      *
      * @param srvReqId Server request ID.
      * @param txt Text.
      * @param nlpSens NLP sentences.
      * @param usrId User ID.
      * @param senMeta Sentence meta data.
      * @param mdlId Model ID.
      * @param logEnable Log enabled flag.
      * @param parent Optional parent span.
      */
    @throws[NCE]
    def ask(
        srvReqId: String,
        txt: String,
        nlpSens: Seq[NCNlpSentence],
        usrId: Long,
        senMeta: Map[String, Serializable],
        mdlId: String,
        logEnable: Boolean,
        parent: Span = null
    ): Unit = {
        val span = startSpan("ask", parent,
            "srvReqId" → srvReqId,
            "txt" → txt,
            "usrId" → usrId,
            "mdlId" → mdlId
        )
    
        try
            ask0(
                srvReqId,
                txt,
                nlpSens,
                usrId,
                senMeta,
                mdlId,
                logEnable,
                span
            )
        catch {
            case e: Throwable ⇒
                span.setStatus(Status.INTERNAL.withDescription(e.getMessage))
                
                U.prettyError(logger,"Failed to process request:", e)
            
                val msg = NCProbeMessage("P2S_ASK_RESULT",
                    "srvReqId" → srvReqId,
                    "error" → "Processing failed due to a system error.",
                    "errorCode" → UNEXPECTED_ERROR,
                    "mdlId" → mdlId,
                    "txt" → txt
                )
            
                try
                    NCConnectionManager.send(msg, span)
                finally
                    span.end()
        }
    }

    /**
      * Processes 'ask' request from probe server.
      *
      * @param srvReqId Server request ID.
      * @param txt Text.
      * @param nlpSens NLP sentences.
      * @param usrId User ID.
      * @param senMeta Sentence meta data.
      * @param mdlId Model ID.
      * @param logEnable Log enable flag.
      */
    @throws[NCE]
    private def ask0(
        srvReqId: String,
        txt: String,
        nlpSens: Seq[NCNlpSentence],
        usrId: Long,
        senMeta: Map[String, Serializable],
        mdlId: String,
        logEnable: Boolean,
        span: Span
    ): Unit = {
        require(nlpSens.nonEmpty)

        var start = System.currentTimeMillis()

        val tbl = NCAsciiTable()

        tbl += (s"${b("Text")}", nlpSens.map(s ⇒ rv(s.text)))
        tbl += (s"${b("Model ID")}", mdlId)
        tbl += (s"${b("User:")}", "")
        tbl += (s"${b("  ID")}", usrId)
        tbl += (s"${b("  First Name")}", senMeta.getOrElse("FIRST_NAME", ""))
        tbl += (s"${b("  Last Name")}", senMeta.getOrElse("LAST_NAME", ""))
        tbl += (s"${b("  Email")}", senMeta.getOrElse("EMAIL", ""))
        tbl += (s"${b("  Company Name")}", senMeta.getOrElse("COMPANY_NAME", ""))
        tbl += (s"${b("  Is Admin")}", senMeta.getOrElse("IS_ADMIN", ""))
        tbl += (s"${b("  Signup Date")}", new Date(java.lang.Long.parseLong(senMeta("SIGNUP_TSTAMP").toString)))
        tbl += (s"${b("User Agent")}", senMeta.getOrElse("USER_AGENT", ""))
        tbl += (s"${b("Remote Address")}", senMeta.getOrElse("REMOTE_ADDR", ""))
        tbl += (s"${b("Server Timestamp")}", new Date(java.lang.Long.parseLong(senMeta("RECEIVE_TSTAMP").toString)))
        tbl += (s"${b("Server Request ID")}", rv(g(srvReqId)))

        logger.info(s"New request received from server:\n$tbl")
        
        /**
          *
          * @param code Pre or post checker error code.
          */
        def getError(code: String): (String, Int) =
            code match {
                case "MAX_UNKNOWN_WORDS" ⇒ "Too many unknown words." → MAX_UNKNOWN_WORDS
                case "MAX_FREE_WORDS" ⇒ "Sentence is too complex." → MAX_FREE_WORDS
                case "MAX_SUSPICIOUS_WORDS" ⇒ "Too many suspicious or unrelated words." → MAX_SUSPICIOUS_WORDS
                case "ALLOW_SWEAR_WORDS" ⇒ "Swear words are not allowed." → ALLOW_SWEAR_WORDS
                case "ALLOW_NO_NOUNS" ⇒ "Sentence contains no nouns." → ALLOW_NO_NOUNS
                case "ALLOW_NON_LATIN_CHARSET" ⇒ "Only latin charset is supported." → ALLOW_NON_LATIN_CHARSET
                case "ALLOW_NON_ENGLISH" ⇒ "Only english language is supported." → ALLOW_NON_ENGLISH
                case "ALLOW_NO_USER_TOKENS" ⇒ "Sentence seems unrelated to the data model." → ALLOW_NO_USER_TOKENS
                case "MIN_WORDS" ⇒ "Sentence is too short." → MIN_WORDS
                case "MIN_NON_STOPWORDS" ⇒ "Sentence is ambiguous." → MIN_NON_STOPWORDS
                case "MIN_TOKENS" ⇒ "Sentence is too short." → MIN_TOKENS
                case "MAX_TOKENS" ⇒ "Sentence is too long." → MAX_TOKENS
                case _ ⇒ s"System error: $code." → UNEXPECTED_ERROR
            }

        /**
          * Makes and sends response.
          *
          * @param resType Result type.
          * @param resBody Result body.
          * @param errMsg Error message.
          * @param errCode Error code.
          * @param msgName Message name.
          * @param log Log data.
          * @param intentId Intent ID.
          */
        def respond(
            resType: Option[String],
            resBody: Option[String],
            errMsg: Option[String],
            errCode: Option[Int],
            msgName: String,
            log: Option[String],
            intentId: Option[String]
        ): Unit = {
            require(errMsg.isDefined || (resType.isDefined && resBody.isDefined))

            val msg = NCProbeMessage(msgName)

            msg += "srvReqId" → srvReqId
            msg += "mdlId" → mdlId
            msg += "txt" → txt

            def addOptional(name: String, vOpt: Option[Serializable]): Unit =
                if (vOpt.isDefined)
                    msg += name → vOpt.get

            if (resBody.isDefined && resBody.get.length > Config.resultMaxSize) {
                addOptional("error", Some("Result is too big. Model result must be corrected."))
                addOptional("errorCode", Some(RESULT_TOO_BIG))
            }
            else {
                addOptional("error", errMsg)
                addOptional("errorCode", errCode.map(Integer.valueOf))
                addOptional("resType", resType)
                addOptional("resBody", resBody)
                addOptional("log", log)
                addOptional("intentId", intentId)
            }

            if (embeddedCbs.nonEmpty) {
                val embRes: NCEmbeddedResult = new NCEmbeddedResult {
                    override val getModelId: String = mdlId
                    override val getServerRequestId: String = srvReqId
                    override val getOriginalText: String = txt
                    override val getUserId: Long = usrId
                    override val getBody: String = msg.dataOpt[String]("resBody").orNull
                    override val getType: String = msg.dataOpt[String]("resType").orNull
                    override val getErrorMessage: String = msg.dataOpt[String]("error").orNull
                    override val getErrorCode: Int = msg.dataOpt[Int]("errorCode").getOrElse(0)
                    override def getProbeId: String = Config.id
                    override def getLogHolder: String = log.orNull
                    override def getIntentId: String = intentId.orNull
                }

                // Call all embedded callbacks first.
                embeddedCbs.foreach(_.accept(embRes))
            }

            NCConnectionManager.send(msg, span)
            
            if (errMsg.isEmpty)
                logger.info(s"OK result sent back to server [srvReqId=$srvReqId, type=${resType.getOrElse("")}]")
            else
                logger.info(s"REJECT response sent back to server [srvReqId=$srvReqId, response=${errMsg.get}]")
        }

        val mdl = NCModelManager.getModel(mdlId, span)

        var errData: Option[(String, Int)] = None

        val validNlpSens =
            nlpSens.flatMap(nlpSen ⇒
                try {
                    NCValidateManager.preValidate(mdl, nlpSen, span)

                    Some(nlpSen)
                }
                catch {
                    case e: NCValidateException ⇒
                        val (errMsg, errCode) = getError(e.code)

                        if (errData.isEmpty)
                            errData = Some((errMsg, errCode))

                        logger.error(s"Pre-enrichment validation error [text=${nlpSen.text}, error=$errMsg]")

                        None
                }
            )

        if (validNlpSens.isEmpty) {
            require(errData.isDefined)

            val (errMsg, errCode) = errData.get

            respond(
                None,
                None,
                Some(errMsg),
                Some(errCode),
                "P2S_ASK_RESULT",
                None,
                None
            )

            return
        }

        val sensSeq = validNlpSens.flatMap(nlpSen ⇒ {
            // Independent of references.
            NCDictionaryEnricher.enrich(mdl, nlpSen, senMeta, span)
            NCSuspiciousNounsEnricher.enrich(mdl, nlpSen, senMeta, span)
            NCStopWordEnricher.enrich(mdl, nlpSen, senMeta, span)

            case class Holder(enricher: NCProbeEnricher, getNotes: () ⇒ Seq[NCNlpSentenceNote])

            def get(name: String, e: NCProbeEnricher): Option[Holder] =
                if (mdl.model.getEnabledBuiltInTokens.contains(name))
                    Some(Holder(e, () ⇒ nlpSen.flatten.filter(_.noteType == name)))
                else
                    None

            val loopEnrichers = Seq(
                Some(Holder(NCModelEnricher, () ⇒ nlpSen.flatten.filter(_.isUser))),
                get("nlpcraft:sort", NCSortEnricher),
                get("nlpcraft:limit", NCLimitEnricher),
                get("nlpcraft:relation", NCRelationEnricher)
            ).flatten

            var step = 0
            var continue = true

            while (continue) {
                step = step + 1

                if (step >= MAX_NESTED_TOKENS)
                    throw new NCE(s"Stack overflow on nested tokens processing (> $MAX_NESTED_TOKENS).")

                val res = loopEnrichers.map(h ⇒ {
                    def get(): Seq[NCNlpSentenceNote] = h.getNotes().sortBy(p ⇒ (p.tokenIndexes.head, p.noteType))
                    val notes1 = get()

                    h.enricher.enrich(mdl, nlpSen, senMeta, span)

                    val notes2 = get()

                    var same = notes1 == notes2

                    if (!same) {
                        def squeeze(typ: String): Boolean = {
                            val diff = notes2.filter(n ⇒ !notes1.contains(n))

                            val diffRedundant = diff.flatMap(n2 ⇒
                                notes1.find(n1 ⇒ nlpSen.notesEqualOrSimilar(n1, n2)) match {
                                    case Some(similar) ⇒ Some(n2 → similar)
                                    case None ⇒ None
                                }
                            )

                            diffRedundant.foreach { case (del, similar) ⇒
                                if (DEEP_DEBUG)
                                    logger.trace(s"Redundant note removed, because similar exists [" +
                                        s"note=$del, " +
                                        s"similar=$similar, " +
                                        s"type=$typ" +
                                    s"]")

                                nlpSen.removeNote(del)
                            }

                            diffRedundant.size == diff.size
                        }

                        h.enricher match {
                            case NCSortEnricher ⇒ same = squeeze("nlpcraft:sort")
                            case NCLimitEnricher ⇒ same = squeeze("nlpcraft:limit")
                            case NCRelationEnricher ⇒ same = squeeze("nlpcraft:relation")

                            case _ ⇒ // No-op.
                        }
                    }

                    h.enricher → same
                }).toMap

                // Loop has sense if model is complex (has user defined parsers or DSL based synonyms)
                continue = NCModelEnricher.isComplex(mdl) && res.exists { case (_, same) ⇒ !same }

                if (DEEP_DEBUG)
                    if (continue) {
                        val changed = res.filter(!_._2).keys.map(_.getClass.getSimpleName).mkString(", ")

                        logger.trace(s"Enrichment iteration finished - more needed [" +
                            s"step=$step, " +
                            s"changed=$changed" +
                        s"]")
                    }
                    else
                        logger.trace(s"Enrichment finished [" +
                            s"step=$step" +
                        s"]")
            }

            nlpSen.clone().collapse().
                // Sorted to support deterministic logs.
                sortBy(p ⇒
                p.map(p ⇒ {
                    val data = p.
                        filter(!_.isNlp).
                        map(Objects.toString).
                        toSeq.
                        sorted.
                        mkString("|")

                    s"${p.origText} $data"
                }).mkString("-")
            )
        })

        // Final validation before execution.
        try
            sensSeq.foreach(NCValidateManager.postValidate(mdl, _, span))
        catch {
            case e: NCValidateException ⇒
                val (errMsg, errCode) = getError(e.code)

                logger.error(s"Post-enrichment validation error: $errMsg")

                respond(
                    None,
                    None,
                    Some(errMsg),
                    Some(errCode),
                    "P2S_ASK_RESULT",
                    None,
                    None
                )

                return
        }

        val meta = mutable.HashMap.empty[String, Any] ++ senMeta
        val req = NCRequestImpl(meta, srvReqId)

        var senVars = NCProbeVariants.convert(srvReqId, mdl, sensSeq)

        // Sentence variants can be filtered by model.
        val fltSenVars: Seq[(NCVariant, Int)] =
            senVars.
            zipWithIndex.
            flatMap { case (variant, i) ⇒ if (mdl.model.onParsedVariant(variant)) Some(variant, i) else None }

        senVars = fltSenVars.map(_._1)
        val allVars = senVars.flatMap(_.asScala)

        // Prints here only filtered variants.
        val fltIdxs = fltSenVars.map { case (_, i) ⇒ i }

        startScopedSpan("logVariants", span) { _ ⇒
            val seq = senVars
            val txt = req.getNormalizedText

            seq.
                zipWithIndex.
                flatMap { case (sen, i) ⇒ if (fltIdxs.contains(i)) Some(sen) else None }.
                zipWithIndex.foreach { case (sen, i) ⇒
                NCTokenLogger.prepareTable(sen.asScala).
                    info(
                        logger,
                        Some(s"Parsing variant #${i + 1} of ${senVars.size} (sorted best to worst) for: '$txt'")
                    )
            }
        }

        val conv = NCConversationManager.getConversation(usrId, mdlId, span)

        // Update STM and recalculate context.
        conv.updateTokens(span)

        var logKey: String = null
        val logHldr = if (logEnable) new NCLogHolder else null
        
        // Create model query context.
        val ctx: NCContext = new NCContext {
            override lazy val getRequest: NCRequest = req
            override lazy val getModel: NCModel = mdl.model
            override lazy val getServerRequestId: String = srvReqId

            override lazy val getConversation: NCConversation = new NCConversation {
                override def getTokens: util.List[NCToken] = conv.getTokens()
                override def getDialogFlow: util.List[String] = NCDialogFlowManager.getDialogFlow(usrId, mdlId, span).asJava
                override def clearStm(filter: Predicate[NCToken]): Unit = conv.clearTokens(filter)
                override def clearDialog(filter: Predicate[String]): Unit = NCDialogFlowManager.clear(usrId, mdlId, span)
                override def getUserData: util.Map[String, Object] = conv.getUserData
            }

            override def isOwnerOf(tok: NCToken): Boolean = allVars.contains(tok)
            override def getVariants: util.Collection[_ <: NCVariant] = senVars.asJava
        }
    
        if (logHldr != null) {
            logHldr.setContext(ctx)
        
            logKey = U.mkLogHolderKey(srvReqId)
        
            val meta = mdl.model.getMetadata
        
            meta.synchronized {
                meta.put(logKey, logHldr)
            }
        }
        
        recordStats(M_SYS_LATENCY_MS → (System.currentTimeMillis() - start))
    
        /**
         *
         * @param res Query result.
         * @param log Processing log.
         */
        def respondWithResult(res: NCResult, log: Option[String]): Unit = respond(
            Some(res.getType),
            Some(res.getBody),
            None,
            None,
            "P2S_ASK_RESULT",
            log,
            Option(res.getIntentId)
        )
        
        def onFinish(): Unit = {
            if (logKey != null)
                mdl.model.getMetadata.remove(logKey)
            
            span.end()
        }
    
        val solverIn = new NCIntentSolverInput(ctx)

        // Execute model query asynchronously.
        U.asFuture(
            _ ⇒ {
                var res = mdl.model.onContext(ctx)
    
                start = System.currentTimeMillis()
    
                if (res == null && mdl.solver != null)
                    startScopedSpan("intentMatching", span) { _ ⇒
                        res = mdl.solver.solve(solverIn, span)
                    }
                
                if (res == null && mdl.solver == null)
                    throw new IllegalStateException("No intents and no results from model callbacks.")
    
                recordStats(M_USER_LATENCY_MS → (System.currentTimeMillis() - start))

                if (res == null)
                    throw new IllegalStateException("Result cannot be null.")
                if (res.getBody == null)
                    throw new IllegalStateException("Result body cannot be null.")
                if (res.getType == null)
                    throw new IllegalStateException("Result type cannot be null.")

                // Adds input sentence tokens to the ongoing conversation if *some* result
                // was returned. Do not add if result is invalid.
                if (res.getTokens != null)
                    conv.addTokens(srvReqId, res.getTokens.asScala.toSeq)
    
                res
            },
            {
                case e: NCRejection ⇒
                    try {
                        addTags(
                            span,
                            "errCode" → MODEL_REJECTION,
                            "errCodeStr" → "MODEL_REJECTION",
                            "errMsg" → e.getMessage
                        )
    
                        logger.info(s"Rejection [srvReqId=$srvReqId, msg=${e.getMessage}]")
    
                        if (e.getCause != null)
                            logger.info(s"Rejection cause:", e.getCause)
    
                        val res = mdl.model.onRejection(solverIn.intentMatch, e)
    
                        if (res != null)
                            respondWithResult(res, None)
                        else
                            respond(
                                None,
                                None,
                                Some(e.getMessage), // User provided rejection message.
                                Some(MODEL_REJECTION),
                                "P2S_ASK_RESULT",
                                None,
                                None
                            )
                    }
                    finally
                        onFinish()

                case e: Throwable ⇒
                    try {
                        addTags(
                            span,
                            "errCode" → UNEXPECTED_ERROR,
                            "errCodeStr" → "UNEXPECTED_ERROR",
                            "errMsg" → e.getMessage
                        )
                    
                        U.prettyError(logger,s"Unexpected error for server request ID: $srvReqId", e)
        
                        val res = mdl.model.onError(ctx, e)
        
                        if (res != null)
                            respondWithResult(res, None)
                        else
                            respond(
                                None,
                                None,
                                Some("Processing failed with unexpected error."), // System error message.
                                Some(UNEXPECTED_ERROR),
                                "P2S_ASK_RESULT",
                                None,
                                None
                            )
                    }
                    finally
                        onFinish()
            },
            (res: NCResult) ⇒ {
                try {
                    addTags(
                        span,
                        "resType" → res.getType,
                        "resBody" → res.getBody
                    )
                    
                    val res0 = mdl.model.onResult(solverIn.intentMatch, res)

                    respondWithResult(if (res0 != null) res0 else res, if (logHldr != null) Some(logHldr.toJson) else None)
                }
                finally
                    onFinish()
            }
        )(executor)
    }
}
