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

package org.apache.nlpcraft.probe.mgrs.inspections.inspectors

import io.opencensus.trace.Span
import org.apache.nlpcraft.common.inspections.NCInspectionResult
import org.apache.nlpcraft.common.inspections.impl.NCInspectionResultImpl
import org.apache.nlpcraft.common.{NCE, NCService}
import org.apache.nlpcraft.probe.mgrs.model.NCModelManager

import scala.collection.JavaConverters._
import scala.collection.Seq
import scala.concurrent.Future

// TODO:
object NCInspectorMacros extends NCService with NCInspector {
    override def inspect(mdlId: String, inspId: String, args: String, parent: Span = null): Future[NCInspectionResult] =
        startScopedSpan("inspect", parent, "modelId" → mdlId) { _ ⇒
            Future {
                val now = System.currentTimeMillis()

                val mdl = NCModelManager.getModel(mdlId).getOrElse(throw new NCE(s"Model not found: '$mdlId'")).model

                val syns = mdl.getElements.asScala.flatMap(_.getSynonyms.asScala)

                val warns =
                    mdl.getMacros.asScala.keys.
                        // TODO: is it valid check? (simple contains)
                        flatMap(m ⇒ if (syns.exists(_.contains(m))) None else Some(s"Macro is not used: $m")).
                        toSeq

                NCInspectionResultImpl(
                    inspectionId = inspId,
                    modelId = mdlId,
                    inspectionArguments = None,
                    durationMs = System.currentTimeMillis() - now,
                    timestamp = now,
                    errors = Seq.empty,
                    warnings = warns,
                    suggestions = Seq.empty
                )
            }(scala.concurrent.ExecutionContext.Implicits.global)
        }
}
