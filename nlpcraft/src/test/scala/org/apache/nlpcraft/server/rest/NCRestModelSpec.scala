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

package org.apache.nlpcraft.server.rest

import org.apache.nlpcraft.NCTestEnvironment
import org.apache.nlpcraft.examples.alarm.AlarmModel
import org.junit.jupiter.api.Assertions._
import org.junit.jupiter.api.{Disabled, Test}

// Enable it and run if context word server started.
@Disabled
@NCTestEnvironment(model = classOf[AlarmModel], startClient = false)
class NCRestModelSpec extends NCRestSpec {
    @Test
    def test(): Unit = {
        post("model/sugsyn", "mdlId" → "nlpcraft.alarm.ex")(
            ("$.status", (status: String) ⇒ assertEquals("API_OK", status))
        )
        post("model/sugsyn", "mdlId" → "nlpcraft.alarm.ex", "minScore" → 0.5)(
            ("$.status", (status: String) ⇒ assertEquals("API_OK", status))
        )
    }
}