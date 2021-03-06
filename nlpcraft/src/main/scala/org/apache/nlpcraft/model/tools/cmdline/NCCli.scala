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

package org.apache.nlpcraft.model.tools.cmdline

import java.io._
import java.lang.ProcessBuilder.Redirect
import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.text.DateFormat
import java.util
import java.util.Date
import java.util.regex.Pattern
import java.util.zip.ZipInputStream

import com.google.common.base.CaseFormat
import javax.lang.model.SourceVersion
import javax.net.ssl.SSLException
import org.apache.commons.io.IOUtils
import org.apache.commons.io.input.{ReversedLinesFileReader, Tailer, TailerListenerAdapter}
import org.apache.commons.lang3.SystemUtils
import org.apache.commons.lang3.time.DurationFormatUtils
import org.apache.http.HttpResponse
import org.apache.http.client.ResponseHandler
import org.apache.http.client.methods.{HttpGet, HttpPost}
import org.apache.http.client.utils.URIBuilder
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.HttpClients
import org.apache.http.util.EntityUtils
import org.apache.nlpcraft.common._
import org.apache.nlpcraft.common.ansi.NCAnsi._
import org.apache.nlpcraft.common.ansi.{NCAnsi, NCAnsiProgressBar, NCAnsiSpinner}
import org.apache.nlpcraft.common.ascii.NCAsciiTable
import org.apache.nlpcraft.common.version.NCVersion
import org.apache.nlpcraft.model.tools.sqlgen.impl.NCSqlModelGeneratorImpl
import org.jline.reader._
import org.jline.reader.impl.DefaultParser
import org.jline.reader.impl.DefaultParser.Bracket
import org.jline.reader.impl.history.DefaultHistory
import org.jline.terminal.{Terminal, TerminalBuilder}
import org.jline.utils.AttributedString
import org.jline.utils.InfoCmp.Capability
import resource.managed

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.compat.Platform.currentTime
import scala.compat.java8.OptionConverters._
import scala.util.Try
import scala.util.control.Breaks.{break, breakable}
import scala.util.control.Exception.ignoring

/**
 * NLPCraft CLI.
 */
object NCCli extends App {
    private final val NAME = "Apache NLPCraft CLI"

    //noinspection RegExpRedundantEscape
    private final val TAILER_PTRN = Pattern.compile("^.*NC[a-zA-Z0-9]+ started \\[[\\d]+ms\\]$")
    private final val CMD_NAME = Pattern.compile("(^\\s*[\\w-]+)(\\s)")
    private final val CMD_PARAM = Pattern.compile("(\\s)(--?[\\w-]+)")

    // Number of server services that need to be started + 1 progress start.
    // Used for progress bar functionality.
    // +==================================================================+
    // | MAKE SURE TO UPDATE THIS VAR WHEN NUMBER OF SERVICES IS CHANGED. |
    // +==================================================================+
    private final val NUM_SRV_SERVICES = 30 /*services*/ + 1 /*progress start*/

    private final val SRV_BEACON_PATH = ".nlpcraft/server_beacon"
    private final val HIST_PATH = ".nlpcraft/.cli_history"

    private final lazy val VER = NCVersion.getCurrent
    private final lazy val JAVA = U.sysEnv("NLPCRAFT_CLI_JAVA").getOrElse(new File(SystemUtils.getJavaHome, s"bin/java${if (SystemUtils.IS_OS_UNIX) "" else ".exe"}").getAbsolutePath)
    private final lazy val INSTALL_HOME = U.sysEnv("NLPCRAFT_CLI_INSTALL_HOME").getOrElse(SystemUtils.USER_DIR)
    private final lazy val JAVA_CP = U.sysEnv("NLPCRAFT_CLI_JAVA_CP").getOrElse(ManagementFactory.getRuntimeMXBean.getClassPath)
    private final lazy val SCRIPT_NAME = U.sysEnv("NLPCRAFT_CLI_SCRIPT").getOrElse(s"nlpcraft.${if (SystemUtils.IS_OS_UNIX) "sh" else "cmd"}")
    private final lazy val PROMPT = if (SCRIPT_NAME.endsWith("cmd")) ">" else "$"
    private final lazy val IS_SCRIPT = U.sysEnv("NLPCRAFT_CLI").isDefined

    private final val T___ = "    "
    private val OPEN_BRK = Seq('[', '{', '(')
    private val CLOSE_BRK = Seq(']', '}', ')')
    // Pair for each open or close bracket.
    private val BRK_PAIR = OPEN_BRK.zip(CLOSE_BRK).toMap ++ CLOSE_BRK.zip(OPEN_BRK).toMap

    private var exitStatus = 0

    private var term: Terminal = _

    // See NCProbeMdo.
    case class Probe(
        probeToken: String,
        probeId: String,
        probeGuid: String,
        probeApiVersion: String,
        probeApiDate: String,
        osVersion: String,
        osName: String,
        osArch: String,
        startTstamp: Long,
        tmzId: String,
        tmzAbbr: String,
        tmzName: String,
        userName: String,
        javaVersion: String,
        javaVendor: String,
        hostName: String,
        hostAddr: String,
        macAddr: String,
        models: Array[ProbeModel]
    )

    // See NCProbeModelMdo.
    case class ProbeModel(
        id: String,
        name: String,
        version: String,
        enabledBuiltInTokens: Array[String]
    )

    case class ProbeAllResponse(
        probes: Array[Probe],
        status: String
    )

    case class SplitError(index: Int)
        extends Exception

    case class NoLocalServer()
        extends IllegalStateException(s"Local REST server not found.")

    case class MissingParameter(cmd: Command, paramId: String)
        extends IllegalArgumentException(
            s"Missing mandatory parameter $C${"'" + cmd.params.find(_.id == paramId).get.names.head + "'"}$RST, " +
            s"type $C'help --cmd=${cmd.name}'$RST to get help."
        )

    case class MissingMandatoryJsonParameters(cmd: Command, missingParams: Seq[RestSpecParameter], path: String)
        extends IllegalArgumentException(
            s"Missing mandatory JSON parameters (${missingParams.map(s ⇒ y(s.name)).mkString(",")}) " +
            s"for $C${"'" + cmd.name + s" --path=$path'"}$RST, type $C'help --cmd=${cmd.name}'$RST to get help."
        )

    case class InvalidParameter(cmd: Command, paramId: String)
        extends IllegalArgumentException(
            s"Invalid parameter $C${"'" + cmd.params.find(_.id == paramId).get.names.head + "'"}$RST, " +
            s"type $C'help --cmd=${cmd.name}'$RST to get help."
        )

    case class InvalidJsonParameter(cmd: Command, param: String)
        extends IllegalArgumentException(
            s"Invalid JSON parameter $C${"'" + param + "'"}$RST, " +
            s"type $C'help --cmd=${cmd.name}'$RST to get help."
        )

    case class HttpError(httpCode: Int)
        extends IllegalStateException(s"REST error (HTTP ${c(httpCode)}).")

    case class MalformedJson()
        extends IllegalStateException("Malformed JSON.")

    case class TooManyArguments(cmd: Command)
        extends IllegalArgumentException(s"Too many arguments, type $C'help --cmd=${cmd.name}'$RST to get help.")

    case class NotEnoughArguments(cmd: Command)
        extends IllegalArgumentException(s"Not enough arguments, type $C'help --cmd=${cmd.name}'$RST to get help.")

    case class RestSpec(
        path: String,
        desc: String,
        group: String,
        params: Seq[RestSpecParameter]
    )

    sealed trait JsonType

    case object STRING extends JsonType
    case object BOOLEAN extends JsonType
    case object NUMERIC extends JsonType
    case object OBJECT extends JsonType
    case object ARRAY extends JsonType

    case class RestSpecParameter(
        name: String,
        kind: JsonType,
        optional: Boolean = false // Mandatory by default.
    )

    // Project templates for 'gen-project' command.
    private lazy val PRJ_TEMPLATES: Map[String, Seq[String]] = {
        val m = mutable.HashMap.empty[String, Seq[String]]

        try
            managed(new ZipInputStream(U.getStream("cli/templates.zip"))) acquireAndGet { zis ⇒
                var entry = zis.getNextEntry

                while (entry != null) {
                    val buf = new StringWriter

                    IOUtils.copy(zis, buf, StandardCharsets.UTF_8)

                    m += entry.getName → buf.toString.split("\n")

                    entry = zis.getNextEntry
                }
            }
        catch {
            case e: IOException ⇒ throw new NCE(s"Failed to read templates", e)
        }

        m.toMap
    }

    //noinspection DuplicatedCode
    // TODO: this needs to be loaded dynamically from OpenAPI spec.
    private final val REST_SPEC = Seq(
        RestSpec(
            path = "clear/conversation",
            desc = "Clears conversation STM",
            group = "Asking",
            params = Seq(
                RestSpecParameter(name = "acsTok", kind = STRING),
                RestSpecParameter(name = "mdlId", kind = STRING),
                RestSpecParameter(name = "usrId", kind = STRING, optional = true),
                RestSpecParameter(name = "usrExtId", kind = STRING, optional = true)
            )
        ),
        RestSpec(
            "clear/dialog",
            "Clears dialog flow",
            "Asking",
            params = Seq(
                RestSpecParameter(name = "acsTok", kind = STRING),
                RestSpecParameter(name = "mdlId", kind = STRING),
                RestSpecParameter(name = "usrId", kind = STRING, optional = true),
                RestSpecParameter(name = "usrExtId", kind = STRING, optional = true)
            )
        ),
        RestSpec(
            "model/sugsyn",
            "Runs model synonym suggestion tool",
            "Tools",
            params = Seq(
                RestSpecParameter(name = "acsTok", kind = STRING),
                RestSpecParameter(name = "mdlId", kind = STRING),
                RestSpecParameter(name = "minScore", kind = NUMERIC)
            )
        ),
        RestSpec(
            "check",
            "Gets status and result of submitted requests",
            "Asking",
            params = Seq(
                RestSpecParameter(name = "acsTok", kind = STRING),
                RestSpecParameter(name = "usrId", kind = STRING, optional = true),
                RestSpecParameter(name = "usrExtId", kind = STRING, optional = true),
                RestSpecParameter(name = "srvReqIds", kind = ARRAY, optional = true),
                RestSpecParameter(name = "maxRows", kind = NUMERIC, optional = true)
            )
        ),
        RestSpec(
            "cancel",
            "Cancels a question",
            "Asking",
            params = Seq(
                RestSpecParameter(name = "acsTok", kind = STRING),
                RestSpecParameter(name = "usrId", kind = STRING, optional = true),
                RestSpecParameter(name = "usrExtId", kind = STRING, optional = true),
                RestSpecParameter(name = "srvReqIds", kind = ARRAY, optional = true),
            )
        ),
        RestSpec(
            "ask",
            "Asks a question",
            "Asking",
            params = Seq(
                RestSpecParameter(name = "acsTok", kind = STRING),
                RestSpecParameter(name = "usrId", kind = STRING, optional = true),
                RestSpecParameter(name = "usrExtId", kind = STRING, optional = true),
                RestSpecParameter(name = "txt", kind = STRING),
                RestSpecParameter(name = "mdlId", kind = STRING),
                RestSpecParameter(name = "data", kind = OBJECT, optional = true),
                RestSpecParameter(name = "enableLog", kind = BOOLEAN, optional = true),
            )
        ),
        RestSpec(
            "ask/sync",
            "Asks a question in synchronous mode",
            "Asking",
            params = Seq(
                RestSpecParameter(name = "acsTok", kind = STRING),
                RestSpecParameter(name = "usrId", kind = STRING, optional = true),
                RestSpecParameter(name = "usrExtId", kind = STRING, optional = true),
                RestSpecParameter(name = "txt", kind = STRING),
                RestSpecParameter(name = "mdlId", kind = STRING),
                RestSpecParameter(name = "data", kind = OBJECT, optional = true),
                RestSpecParameter(name = "enableLog", kind = BOOLEAN, optional = true),
            )
        ),
        RestSpec(
            "user/get",
            "Gets current user information",
            "User",
            params = Seq(
                RestSpecParameter(name = "acsTok", kind = STRING),
                RestSpecParameter(name = "id", kind = STRING, optional = true),
                RestSpecParameter(name = "usrExtId", kind = STRING, optional = true)
            )
        ),
        RestSpec(
            "user/all",
            "Gets all users",
            "User",
            params = Seq(
                RestSpecParameter(name = "acsTok", kind = STRING),
            )
        ),
        RestSpec(
            "user/update",
            "Updates regular user",
            "User",
            params = Seq(
                RestSpecParameter(name = "acsTok", kind = STRING),
                RestSpecParameter(name = "firstName", kind = STRING),
                RestSpecParameter(name = "lastName", kind = STRING),
                RestSpecParameter(name = "id", kind = STRING, optional = true),
                RestSpecParameter(name = "avatarUrl", kind = STRING, optional = true),
                RestSpecParameter(name = "properties", kind = OBJECT, optional = true)
            )
        ),
        RestSpec(
            "user/delete",
            "Deletes user",
            "User",
            params = Seq(
                RestSpecParameter(name = "acsTok", kind = STRING),
                RestSpecParameter(name = "id", kind = STRING, optional = true),
                RestSpecParameter(name = "usrExtId", kind = STRING, optional = true)
            )
        ),
        RestSpec(
            "user/admin",
            "Updates user admin permissions",
            "User",
            params = Seq(
                RestSpecParameter(name = "acsTok", kind = STRING),
                RestSpecParameter(name = "id", kind = STRING, optional = true),
                RestSpecParameter(name = "isAdmin", kind = BOOLEAN)
            )
        ),
        RestSpec(
            "user/passwd/reset",
            "Resets password for the user",
            "User",
            params = Seq(
                RestSpecParameter(name = "acsTok", kind = STRING),
                RestSpecParameter(name = "id", kind = STRING, optional = true),
                RestSpecParameter(name = "newPasswd", kind = STRING)
            )
        ),
        RestSpec(
            "user/add",
            "Adds new user",
            "User",
            params = Seq(
                RestSpecParameter(name = "acsTok", kind = STRING),
                RestSpecParameter(name = "firstName", kind = STRING),
                RestSpecParameter(name = "lastName", kind = STRING),
                RestSpecParameter(name = "email", kind = STRING),
                RestSpecParameter(name = "passwd", kind = STRING),
                RestSpecParameter(name = "isAdmin", kind = BOOLEAN),
                RestSpecParameter(name = "usrExtId", kind = STRING, optional = true),
                RestSpecParameter(name = "avatarUrl", kind = STRING, optional = true),
                RestSpecParameter(name = "properties", kind = OBJECT, optional = true)
            )
        ),
        RestSpec(
            "company/get",
            "Gets current user company information",
            "Company",
            params = Seq(
                RestSpecParameter(name = "acsTok", kind = STRING),
            )
        ),
        RestSpec(
            "company/add",
            "Adds new company",
            "Company",
            params = Seq(
                RestSpecParameter(name = "acsTok", kind = STRING),
                RestSpecParameter(name = "name", kind = STRING),
                RestSpecParameter(name = "website", kind = STRING, optional = true),
                RestSpecParameter(name = "country", kind = STRING, optional = true),
                RestSpecParameter(name = "region", kind = STRING, optional = true),
                RestSpecParameter(name = "city", kind = STRING, optional = true),
                RestSpecParameter(name = "address", kind = STRING, optional = true),
                RestSpecParameter(name = "postalCode", kind = STRING, optional = true),
                RestSpecParameter(name = "adminEmail", kind = STRING),
                RestSpecParameter(name = "adminPasswd", kind = STRING),
                RestSpecParameter(name = "adminFirstName", kind = STRING),
                RestSpecParameter(name = "adminLastName", kind = STRING),
                RestSpecParameter(name = "adminAvatarUrl", kind = STRING, optional = true)
            )
        ),
        RestSpec(
            "company/update",
            "Updates company data",
            "Company",
            params = Seq(
                RestSpecParameter(name = "acsTok", kind = STRING),
                RestSpecParameter(name = "name", kind = STRING),
                RestSpecParameter(name = "website", kind = STRING, optional = true),
                RestSpecParameter(name = "country", kind = STRING, optional = true),
                RestSpecParameter(name = "region", kind = STRING, optional = true),
                RestSpecParameter(name = "city", kind = STRING, optional = true),
                RestSpecParameter(name = "address", kind = STRING, optional = true),
                RestSpecParameter(name = "postalCode", kind = STRING, optional = true)
            )
        ),
        RestSpec(
            "company/delete",
            "Deletes company",
            "Company",
            params = Seq(
                RestSpecParameter(name = "acsTok", kind = STRING),
            )
        ),
        RestSpec(
            "company/token/reset",
            "Resets company probe auth token",
            "Company",
            params = Seq(
                RestSpecParameter(name = "acsTok", kind = STRING),
            )
        ),
        RestSpec(
            "feedback/add",
            "Adds feedback",
            "Asking",
            params = Seq(
                RestSpecParameter(name = "acsTok", kind = STRING),
                RestSpecParameter(name = "usrId", kind = STRING, optional = true),
                RestSpecParameter(name = "extUsrId", kind = STRING, optional = true),
                RestSpecParameter(name = "comment", kind = STRING, optional = true),
                RestSpecParameter(name = "srvReqId", kind = STRING),
                RestSpecParameter(name = "score", kind = STRING)
            )
        ),
        RestSpec(
            "feedback/delete",
            "Deletes feedback",
            "Asking",
            params = Seq(
                RestSpecParameter(name = "acsTok", kind = STRING),
                RestSpecParameter(name = "id", kind = NUMERIC)
            )
        ),
        RestSpec(
            "feedback/all",
            "Gets all feedback",
            "Asking",
            params = Seq(
                RestSpecParameter(name = "acsTok", kind = STRING),
                RestSpecParameter(name = "usrId", kind = STRING, optional = true),
                RestSpecParameter(name = "extUsrId", kind = STRING, optional = true),
                RestSpecParameter(name = "srvReqId", kind = STRING, optional = true)
            )
        ),
        RestSpec(
            "signin",
            "Signs in and obtains new access token",
            "Authentication",
            params = Seq(
                RestSpecParameter(name = "email", kind = STRING),
                RestSpecParameter(name = "passwd", kind = STRING)
            )
        ),
        RestSpec(
            "signout",
            "Signs out and releases access token",
            "Authentication",
            params = Seq(
                RestSpecParameter(name = "acsTok", kind = STRING),
            )
        ),
        RestSpec(
            "probe/all",
            "Gets all probes",
            "Probe",
            params = Seq(
                RestSpecParameter(name = "acsTok", kind = STRING),
            )
        )
    )

    case class HttpRestResponse(
        code: Int,
        data: String
    )

    case class ReplState(
        var isServerOnline: Boolean = false,
        var accessToken: Option[String] = None,
        var serverLog: Option[File] = None,
        var probes: List[Probe] = Nil // List of connected probes.
    )

    @volatile private var state = ReplState()

    // Single CLI command.
    case class Command(
        name: String,
        group: String,
        synopsis: String,
        desc: Option[String] = None,
        params: Seq[Parameter] = Seq.empty,
        examples: Seq[Example] = Seq.empty,
        body: (Command, Seq[Argument], Boolean) ⇒ Unit
    ) {
        /**
         *
         * @param name
         * @return
         */
        def findParameterByNameOpt(name: String): Option[Parameter] =
            params.find(_.names.contains(name))

        /**
         *
         * @param id
         * @return
         */
        def findParameterByIdOpt(id: String): Option[Parameter] =
            params.find(_.id == id)

        /**
         *
         * @param id
         * @return
         */
        def findParameterById(id: String): Parameter =
            findParameterByIdOpt(id).get
    }

    // Single command's example.
    case class Example(
        usage: Seq[String],
        desc: String
    )

    // Single command's parameter.
    case class Parameter(
        id: String,
        names: Seq[String],
        value: Option[String] = None,
        optional: Boolean = false, // Mandatory by default.
        synthetic: Boolean = false,
        desc: String
    )

    // Parsed command line argument.
    case class Argument(
        parameter: Parameter, // Formal parameter this argument refers to.
        value: Option[String]
    )

    //noinspection DuplicatedCode
    // All supported commands.
    private final val CMDS = Seq(
        Command(
            name = "rest",
            group = "2. REST Commands",
            synopsis = s"REST call in a convenient way for command line mode.",
            desc = Some(
                s"When using this command you supply all call parameters as a single ${y("'--json'")} parameter with a JSON string. " +
                s"In REPL mode, you can hit ${rv(" Tab ")} to see auto-suggestion and auto-completion candidates for " +
                s"commonly used paths. However, ${y("'call'")} command provides more convenient way to issue REST " +
                s"calls when in REPL mode."
            ),
            body = cmdRest,
            params = Seq(
                Parameter(
                    id = "path",
                    names = Seq("--path", "-p"),
                    value = Some("path"),
                    desc =
                        s"REST path, e.g. ${y("'signin'")} or ${y("'ask/sync'")}. " +
                        s"Note that you don't need supply '/' at the beginning. " +
                        s"See more details at https://nlpcraft.apache.org/using-rest.html " +
                        s"In REPL mode, hit ${rv(" Tab ")} to see auto-suggestion for possible REST paths."
                ),
                Parameter(
                    id = "json",
                    names = Seq("--json", "-j"),
                    value = Some("'json'"),
                    desc =
                        s"REST call parameters as JSON object. Since standard JSON only supports double " +
                        s"quotes the entire JSON string should be enclosed in single quotes. You can " +
                        s"find full OpenAPI specification for NLPCraft REST API at " +
                        s"https://nlpcraft.apache.org/using-rest.html"
                )
            ),
            examples = Seq(
                Example(
                    usage = Seq(
                        s"$PROMPT $SCRIPT_NAME rest ",
                        "  -p=signin",
                        "  -j='{\"email\": \"admin@admin.com\", \"passwd\": \"admin\"}'"
                    ),
                    desc = s"Issues ${y("'signin'")} REST call with given JSON payload."
                )
            )
        ),
        Command(
            name = "signin",
            group = "2. REST Commands",
            synopsis = s"Wrapper for ${c("'/signin'")} REST call.",
            desc = Some(
                s"If no arguments provided, it signs in with the " +
                s"default 'admin@admin.com' user account. NOTE: please make sure to remove this account when " +
                s"running in production."
            ),
            body = cmdSignIn,
            params = Seq(
                Parameter(
                    id = "email",
                    names = Seq("--email", "-e"),
                    value = Some("email"),
                    optional = true,
                    desc =
                        s"Email of the user. If not provided, 'admin@admin.com' will be used."
                ),
                Parameter(
                    id = "passwd",
                    names = Seq("--passwd", "-p"),
                    value = Some("****"),
                    optional = true,
                    desc =
                        s"User password to sign in. If not provided, the default password will be used."
                )
            ),
            examples = Seq(
                Example(
                    usage = Seq(
                        s"$PROMPT $SCRIPT_NAME signin"
                    ),
                    desc = s"Signs in with the default ${c("admin@admin.com")} user account."
                )
            )
        ),
        Command(
            name = "signout",
            group = "2. REST Commands",
            synopsis = s"Wrapper for ${c("'/signout'")} REST call.",
            desc = Some(
                s"Signs out currently signed in user."
            ),
            body = cmdSignOut,
            examples = Seq(
                Example(
                    usage = Seq(
                        s"$PROMPT $SCRIPT_NAME signout"
                    ),
                    desc = s"Signs out currently signed in user, if any."
                )
            )
        ),
        Command(
            name = "call",
            group = "2. REST Commands",
            synopsis = s"REST call in a convenient way for REPL mode.",
            desc = Some(
                s"When using this command you supply all call parameters separately through their own parameters named " +
                s"after their corresponding parameters in REST specification. " +
                s"In REPL mode, hit ${rv(" Tab ")} to see auto-suggestion and " +
                s"auto-completion candidates for commonly used paths and call parameters."
            ),
            body = cmdCall,
            params = Seq(
                Parameter(
                    id = "path",
                    names = Seq("--path", "-p"),
                    value = Some("path"),
                    desc =
                        s"REST path, e.g. ${y("'signin'")} or ${y("'ask/sync'")}. " +
                        s"Note that you don't need supply '/' at the beginning. " +
                        s"See more details at https://nlpcraft.apache.org/using-rest.html " +
                        s"In REPL mode, hit ${rv(" Tab ")} to see auto-suggestion for possible REST paths."
                ),
                Parameter(
                    id = "xxx",
                    names = Seq("--xxx"),
                    value = Some("value"),
                    optional = true,
                    synthetic = true,
                    desc =
                        s"${y("'xxx'")} name corresponds to the REST call parameter that can be found at https://nlpcraft.apache.org/using-rest.html " +
                        s"The value of this parameter should be a valid JSON value using valid JSON syntax. Note that strings " +
                        s"don't have to be in double quotes. JSON objects and arrays should be specified as a JSON string in single quotes. You can have " +
                        s"as many ${y("'--xxx=value'")} parameters as requires by the ${y("'--path'")} parameter. " +
                        s"In REPL mode, hit ${rv(" Tab ")} to see auto-suggestion for possible parameters and their values."
                )
            ),
            examples = Seq(
                Example(
                    usage = Seq(
                        s"$PROMPT $SCRIPT_NAME call -p=signin",
                        "  --email=admin@admin.com",
                        "  --passwd=admin"
                    ),
                    desc =
                        s"Issues ${y("'signin'")} REST call with given JSON payload provided as a set of parameters. " +
                        s"Note that ${y("'--email'")} and ${y("'--passwd'")} parameters correspond to the REST call " +
                        s"specification for ${y("'/signin'")} path."
                ),
                Example(
                    usage = Seq(
                        s"$PROMPT $SCRIPT_NAME call --path=ask/sync",
                        "  --acsTok=qwerty123456",
                        "  --txt=\"User request\"",
                        "  --mdlId=my.model.id",
                        "  --data='{\"data1\": true, \"data2\": 123, \"data3\": \"some text\"}'",
                        "  --enableLog=false"
                    ),
                    desc =
                        s"Issues ${y("'ask/sync'")} REST call with given JSON payload provided as a set of parameters."
                )
            )
        ),
        Command(
            name = "ask",
            group = "2. REST Commands",
            synopsis = s"Wrapper for ${c("'/ask/sync'")} REST call.",
            desc = Some(
                s"Requires user to be already signed in. This command ${bo("only makes sense in the REPL mode")} as " +
                s"it requires user to be signed in. REPL session keeps the currently active access " +
                s"token after user signed in. For command line mode, use ${c("'rest'")} command with " +
                s"corresponding parameters."
            ),
            body = cmdAsk,
            params = Seq(
                Parameter(
                    id = "mdlId",
                    names = Seq("--mdlId"),
                    value = Some("model.id"),
                    desc =
                        s"ID of the data model to send the request to. " +
                        s"In REPL mode, hit ${rv(" Tab ")} to see auto-suggestion for possible model IDs."
                ),
                Parameter(
                    id = "txt",
                    names = Seq("--txt"),
                    value = Some("txt"),
                    desc =
                        s"Text of the question."
                ),
                Parameter(
                    id = "data",
                    names = Seq("--data"),
                    value = Some("'{}'"),
                    optional = true,
                    desc = s"Additional JSON data with maximum JSON length of 512000 bytes. Default is ${c("'null'")}."
                ),
                Parameter(
                    id = "enableLog",
                    names = Seq("--enableLog"),
                    value = Some("true|false"),
                    optional = true,
                    desc = s"Flag to enable detailed processing log to be returned with the result. Default is ${c("'false'")}."
                )
            ),
            examples = Seq(
                Example(
                    usage = Seq(
                        s"""> ask --txt="User request" --mdlId=my.model.id"""
                    ),
                    desc =
                        s"Issues ${y("'ask/sync'")} REST call with given text and model ID."
                )
            )
        ),
        Command(
            name = "gen-sql",
            group = "4. Miscellaneous Tools",
            synopsis = s"Generates NLPCraft model stub from SQL databases.",
            desc = Some(
                s"You can choose database schema, set of tables and columns for which you want to generate NLPCraft " +
                s"model. After the model is generated you can further configure and customize it for your specific needs."
            ),
            body = cmdSqlGen,
            params = Seq(
                Parameter(
                    id = "url",
                    names = Seq("--url", "-r"),
                    value = Some("url"),
                    desc =
                        s"Database JDBC URL."
                ),
                Parameter(
                    id = "driver",
                    names = Seq("--driver", "-d"),
                    value = Some("class"),
                    desc =
                        s"Mandatory JDBC driver class. Note that 'class' must be a fully qualified class name. " +
                        s"It should also be available on the classpath."
                ),
                Parameter(
                    id = "schema",
                    names = Seq("--schema", "-s"),
                    value = Some("schema"),
                    desc =
                        s"Database schema to scan."
                ),
                Parameter(
                    id = "out",
                    names = Seq("--out", "-o"),
                    value = Some("filename"),
                    desc =
                        s"Name of the output JSON or YAML model file. " +
                        s"It should have one of the following extensions: .js, .json, .yml, or .yaml. " +
                        s"File extension determines the output file format."
                ),
                Parameter(
                    id = "user",
                    names = Seq("--user", "-u"),
                    value = Some("username"),
                    optional = true,
                    desc = s"Database user name."
                ),
                Parameter(
                    id = "password",
                    names = Seq("--password", "-w"),
                    value = Some("password"),
                    optional = true,
                    desc = s"Database password."
                ),
                Parameter(
                    id = "modelId",
                    names = Seq("--model-id", "-x"),
                    value = Some("id"),
                    optional = true,
                    desc = s"Generated model ID. By default, the model ID is ${c("'sql.model.id'")}."
                ),
                Parameter(
                    id = "modelVer",
                    names = Seq("--model-ver", "-v"),
                    value = Some("version"),
                    optional = true,
                    desc = s"Generated model version. By default, the model version is ${c("'1.0.0-timestamp'")}."
                ),
                Parameter(
                    id = "modelName",
                    names = Seq("--model-name", "-n"),
                    value = Some("name"),
                    optional = true,
                    desc = s"Generated model name. By default, the model name is ${c("'SQL-based-model'")}."
                ),
                Parameter(
                    id = "exclude",
                    names = Seq("--exclude", "-e"),
                    value = Some("list"),
                    optional = true,
                    desc =
                        s"Semicolon-separate list of tables and/or columns to exclude. By default, none of the " +
                        s"tables and columns in the schema are excluded. See ${c("--help")} parameter to get more details."
                ),
                Parameter(
                    id = "include",
                    names = Seq("--include", "-i"),
                    value = Some("list"),
                    optional = true,
                    desc =
                        s"Semicolon-separate list of tables and/or columns to include. By default, all of the " +
                        s"tables and columns in the schema are included. See ${c("--help")} parameter to get more details."
                ),
                Parameter(
                    id = "prefix",
                    names = Seq("--prefix", "-f"),
                    value = Some("list"),
                    optional = true,
                    desc =
                        s"Comma-separate list of table or column name prefixes to remove. These prefixes will be " +
                        s"removed when name is used for model elements synonyms. By default, no prefixes will be removed."
                ),
                Parameter(
                    id = "suffix",
                    names = Seq("--suffix", "-q"),
                    value = Some("list"),
                    optional = true,
                    desc =
                        s"Comma-separate list of table or column name suffixes to remove. These suffixes will be " +
                        s"removed when name is used for model elements synonyms. By default, no suffixes will be removed."
                ),
                Parameter(
                    id = "synonyms",
                    names = Seq("--synonyms", "-y"),
                    value = Some("true|false"),
                    optional = true,
                    desc = s"Flag on whether or not to generated auto synonyms for the model elements. Default is ${c("'true'")}."
                ),
                Parameter(
                    id = "override",
                    names = Seq("--override", "-z"),
                    value = Some("true|false"),
                    optional = true,
                    desc =
                        s"Flag to determine whether or not to override output file if it already exist. " +
                        s"If override is disabled (default) and output file exists - a unique file name " +
                        s"will be used instead. Default is ${c("'false'")}."
                ),
                Parameter(
                    id = "parent",
                    names = Seq("--parent", "-p"),
                    value = Some("true|false"),
                    optional = true,
                    desc =
                        s"Flag on whether or not to use element's parent relationship for defining " +
                        s"SQL columns and their containing (i.e. parent) tables. Default is ${c("'false'")}."
                ),
                Parameter(
                    id = "help",
                    names = Seq("--help", "-h"),
                    optional = true,
                    desc =
                        s"Gets extended help and usage information for the ${c("'gen-sql'")} command. " +
                        s"Includes information on how to run this tool standalone."
                )
            ),
            examples = Seq(
                Example(
                    usage = Seq(
                        s"$PROMPT $SCRIPT_NAME gen-sql --help"
                    ),
                    desc =
                        s"Shows full help and usage information for ${c("gen-sql")} command."
                ),
                Example(
                    usage = Seq(
                        s"$PROMPT $SCRIPT_NAME gen-sql",
                        "  -r=jdbc:postgresql://localhost:5432/mydb",
                        "  -d=org.postgresql.Driver",
                        """  -f="tbl_, col_"""",
                        """  -q="_tmp, _old, _unused"""",
                        "  -s=public",
                        """  -e="#_.+"""",
                        "  -o=model.json"
                    ),
                    desc =
                        s"Generates model stub from given SQL database connection."
                )
            )
        ),
        Command(
            name = "sugsyn",
            group = "2. REST Commands",
            synopsis = s"Wrapper for ${c("'/model/sugsyn'")} REST call.",
            desc = Some(
                s"Requires user to be already signed in. This command ${bo("only makes sense in the REPL mode")} as " +
                s"it requires user to be signed in. REPL session keeps the currently active access " +
                s"token after user signed in. For command line mode, use ${c("'rest'")} command with " +
                s"corresponding parameters."
            ),
            body = cmdSugSyn,
            params = Seq(
                Parameter(
                    id = "mdlId",
                    names = Seq("--mdlId"),
                    value = Some("model.id"),
                    desc =
                        s"ID of the model to run synonym suggestion on. " +
                        s"In REPL mode, hit ${rv(" Tab ")} to see auto-suggestion for possible model IDs."
                ),
                Parameter(
                    id = "minScore",
                    names = Seq("--minScore"),
                    value = Some("0.5"),
                    optional = true,
                    desc = s"Minimal score to include into the result (from 0 to 1). Default is ${c("0.5")}."
                )
            ),
            examples = Seq(
                Example(
                    usage = Seq(
                        s"""> sugsyn --mdlId=my.model.id"""
                    ),
                    desc =
                        s"Issues ${y("'model/sugsyn'")} REST call with default min score and given model ID."
                )
            )
        ),
        Command(
            name = "tail-server",
            group = "1. Server Commands",
            synopsis = s"Shows last N lines from the local REST server log.",
            desc = Some(
                s"Only works for the server started via this script."
            ),
            body = cmdTailServer,
            params = Seq(
                Parameter(
                    id = "lines",
                    names = Seq("--lines", "-l"),
                    value = Some("num"),
                    desc =
                        s"Number of the server log lines from the end to display. Default is 20."
                )
            ),
            examples = Seq(
                Example(
                    usage = Seq(s"$PROMPT $SCRIPT_NAME tail-server --lines=20 "),
                    desc = s"Prints last 20 lines from the local server log."
                )
            )
        ),
        Command(
            name = "start-server",
            group = "1. Server Commands",
            synopsis = s"Starts local REST server.",
            desc = Some(
                s"REST server is started in the external JVM process with both stdout and stderr piped out into log file. " +
                s"Command will block until the server is started unless ${y("'--no-wait'")} parameter is used."
            ),
            body = cmdStartServer,
            params = Seq(
                Parameter(
                    id = "config",
                    names = Seq("--config", "-c"),
                    value = Some("path"),
                    optional = true,
                    desc =
                        s"Configuration absolute file path. Server will automatically look for ${y("'nlpcraft.conf'")} " +
                        s"configuration file in the same directory as NLPCraft JAR file. If the configuration file has " +
                        s"different name or in different location use this parameter to provide an alternative path. " +
                        s"Note that the REST server and the data probe can use the same file for their configuration."
                ),
                Parameter(
                    id = "igniteConfig",
                    names = Seq("--ignite-config", "-i"),
                    value = Some("path"),
                    optional = true,
                    desc =
                        s"Apache Ignite configuration absolute file path. Note that Apache Ignite is used as a cluster " +
                        s"computing plane and a default distributed storage. REST server will automatically look for " +
                        s"${y("'ignite.xml'")} configuration file in the same directory as NLPCraft JAR file. If the " +
                        s"configuration file has different name or in different location use this parameter to " +
                        s"provide an alternative path."
                ),
                Parameter(
                    id = "noWait",
                    names = Seq("--no-wait"),
                    optional = true,
                    desc =
                        s"Instructs command not to wait for the server startup and return immediately."
                )
            ),
            examples = Seq(
                Example(
                    usage = Seq(s"$PROMPT $SCRIPT_NAME start-server"),
                    desc = "Starts local server with default configuration."
                ),
                Example(
                    usage = Seq(s"$PROMPT $SCRIPT_NAME start-server -c=/opt/nlpcraft/nlpcraft.conf"),
                    desc = "Starts local REST server with alternative configuration file."
                )
            )
        ),
        Command(
            name = "restart-server",
            group = "1. Server Commands",
            synopsis = s"Restarts local REST server.",
            desc = Some(
                s"This command is equivalent to executing  ${y("'stop-server'")} and then ${y("'start-server'")} commands with " +
                s"corresponding parameters. If there is no local REST server the ${y("'stop-server'")} command is ignored."
            ),
            body = cmdRestartServer,
            params = Seq(
                Parameter(
                    id = "config",
                    names = Seq("--config", "-c"),
                    value = Some("path"),
                    optional = true,
                    desc =
                        s"Configuration absolute file path. Server will automatically look for ${y("'nlpcraft.conf'")} " +
                        s"configuration file in the same directory as NLPCraft JAR file. If the configuration file has " +
                        s"different name or in different location use this parameter to provide an alternative path. " +
                        s"Note that the REST server and the data probe can use the same file for their configuration."
                ),
                Parameter(
                    id = "igniteConfig",
                    names = Seq("--ignite-config", "-i"),
                    value = Some("path"),
                    optional = true,
                    desc =
                        s"Apache Ignite configuration absolute file path. Note that Apache Ignite is used as a cluster " +
                        s"computing plane and a default distributed storage. REST server will automatically look for " +
                        s"${y("'ignite.xml'")} configuration file in the same directory as NLPCraft JAR file. If the " +
                        s"configuration file has different name or in different location use this parameter to " +
                        s"provide an alternative path."
                ),
                Parameter(
                    id = "noWait",
                    names = Seq("--no-wait"),
                    optional = true,
                    desc =
                        s"Instructs command not to wait for the server startup and return immediately."
                )
            ),
            examples = Seq(
                Example(
                    usage = Seq(s"$PROMPT $SCRIPT_NAME start-server"),
                    desc = "Starts local server with default configuration."
                ),
                Example(
                    usage = Seq(s"$PROMPT $SCRIPT_NAME start-server -c=/opt/nlpcraft/nlpcraft.conf"),
                    desc = "Starts local REST server with alternative configuration file."
                )
            )
        ),
        Command(
            name = "info-server",
            group = "1. Server Commands",
            synopsis = s"Info about local REST server.",
            body = cmdInfoServer
        ),
        Command(
            name = "cls",
            group = "3. REPL Commands",
            synopsis = s"Clears terminal screen.",
            body = cmdCls
        ),
        Command(
            name = "no-ansi",
            group = "3. REPL Commands",
            synopsis = s"Disables ANSI escape codes for terminal colors & controls.",
            desc = Some(
                s"This is a special command that can be combined with any other commands."
            ),
            body = cmdNoAnsi,
            examples = Seq(
                Example(
                    usage = Seq(s"$PROMPT $SCRIPT_NAME help -c=rest no-ansi"),
                    desc = s"Displays help for ${y("'rest'")} commands without using ANSI color and escape sequences."
                )
            )
        ),
        Command(
            name = "ansi",
            group = "3. REPL Commands",
            synopsis = s"Enables ANSI escape codes for terminal colors & controls.",
            desc = Some(
                s"This is a special command that can be combined with any other commands."
            ),
            body = cmdAnsi,
            examples = Seq(
                Example(
                    usage = Seq(s"$PROMPT $SCRIPT_NAME help -c=rest ansi"),
                    desc = s"Displays help for ${y("'rest'")} commands with ANSI color and escape sequences."
                )
            )
        ),
        Command(
            name = "ping-server",
            group = "1. Server Commands",
            synopsis = s"Pings local REST server.",
            desc = Some(
                s"REST server is pinged using ${y("'/health'")} REST call to check its online status."
            ),
            body = cmdPingServer,
            params = Seq(
                Parameter(
                    id = "number",
                    names = Seq("--number", "-n"),
                    value = Some("num"),
                    optional = true,
                    desc =
                        "Number of pings to perform. Must be a number > 0. Default is 1."
                )
            ),
            examples = Seq(
                Example(
                    usage = Seq(
                        s"$PROMPT $SCRIPT_NAME ping-server -n=10"
                    ),
                    desc = "Pings local REST server 10 times."
                )
            )
        ),
        Command(
            name = "stop-server",
            group = "1. Server Commands",
            synopsis = s"Stops local REST server.",
            desc = Some(
                s"Local REST server must be started via ${y(s"'$SCRIPT_NAME''")} or other compatible way."
            ),
            body = cmdStopServer
        ),
        Command(
            name = "quit",
            group = "3. REPL Commands",
            synopsis = s"Quits REPL mode.",
            body = cmdQuit
        ),
        Command(
            name = "help",
            group = "3. REPL Commands",
            synopsis = s"Displays help for ${y(s"'$SCRIPT_NAME'")}.",
            desc = Some(
                s"By default, without ${y("'--all'")} or ${y("'--cmd'")} parameters, displays the abbreviated form of manual " +
                    s"only listing the commands without parameters or examples."
            ),
            body = cmdHelp,
            params = Seq(
                Parameter(
                    id = "cmd",
                    names = Seq("--cmd", "-c"),
                    value = Some("cmd"),
                    optional = true,
                    desc = "Set of commands to show the manual for. Can be used multiple times."
                ),
                Parameter(
                    id = "all",
                    names = Seq("--all", "-a"),
                    optional = true,
                    desc = "Flag to show full manual for all commands."
                )
            ),
            examples = Seq(
                Example(
                    usage = Seq(s"$PROMPT $SCRIPT_NAME help -c=rest --cmd=version"),
                    desc = s"Displays help for ${y("'rest'")} and ${y("'version'")} commands."
                ),
                Example(
                    usage = Seq(s"$PROMPT $SCRIPT_NAME help -all"),
                    desc = "Displays help for all commands."
                )
            )
        ),
        Command(
            name = "version",
            group = "3. REPL Commands",
            synopsis = s"Displays full version of ${y(s"'$SCRIPT_NAME'")} script.",
            desc = Some(
                "Depending on the additional parameters can display only the semantic version or the release date."
            ),
            body = cmdVersion,
            params = Seq(
                Parameter(
                    id = "semver",
                    names = Seq("--sem-ver", "-s"),
                    value = None,
                    optional = true,
                    desc = s"Display only the semantic version value, e.g. ${VER.version}."
                ),
                Parameter(
                    id = "reldate",
                    names = Seq("--rel-date", "-d"),
                    value = None,
                    optional = true,
                    desc = s"Display only the release date, e.g. ${VER.date}."
                )
            )
        ),
        Command(
            name = "gen-project",
            group = "4. Miscellaneous Tools",
            synopsis = s"Generates project stub with default configuration.",
            desc = Some(
                "This command supports Java, Scala, and Kotlin languages with either Maven, Gradle or SBT " +
                "as a build tool. Generated projects compiles and runs and can be used as a quick development sandbox."
            ),
            body = cmdGenProject,
            params = Seq(
                Parameter(
                    id = "outputDir",
                    names = Seq("--outputDir", "-d"),
                    value = Some("path"),
                    optional = true,
                    desc = s"Output directory. Default value is the current working directory."
                ),
                Parameter(
                    id = "baseName",
                    names = Seq("--baseName", "-n"),
                    value = Some("name"),
                    desc =
                        s"Base name for the generated files. For example, if base name is ${y("'MyApp'")}, " +
                        s"then generated Java file will be named as ${y("'MyAppModel.java'")} and model file as ${y("'my_app_model.yaml'")}."
                ),
                Parameter(
                    id = "lang",
                    names = Seq("--lang", "-l"),
                    value = Some("name"),
                    optional = true,
                    desc =
                        s"Language to generate source files in. Supported value are ${y("'java'")}, ${y("'scala'")}, ${y("'kotlin'")}. " +
                        s"Default value is ${y("'java'")}."
                ),
                Parameter(
                    id = "buildTool",
                    names = Seq("--buildTool", "-b"),
                    value = Some("name"),
                    optional = true,
                    desc =
                        s"Build tool name to use. Supported values are ${y("'mvn'")} and ${y("'gradle'")} for ${y("'java'")}, " +
                        s"${y("'scala'")}, ${y("'kotlin'")}, and ${y("'sbt'")} for ${y("'scala'")} language. Default value is ${y("'mvn'")}."
                ),
                Parameter(
                    id = "packageName",
                    names = Seq("--packageName", "-p"),
                    value = Some("name"),
                    optional = true,
                    desc = s"JVM package name to use in generated source code. Default value is ${y("'org.apache.nlpcraft.demo'")}."
                ),
                Parameter(
                    id = "modelType",
                    names = Seq("--modelType", "-m"),
                    value = Some("type"),
                    optional = true,
                    desc = s"Type of generated model file. Supported value are ${y("'yaml'")} or ${y("'json'")}. Default value is ${y("'yaml'")}."
                ),
                Parameter(
                    id = "override",
                    names = Seq("--override", "-o"),
                    value = Some("true|false"),
                    optional = true,
                    desc = s"Whether or not to override existing output directory. Default value is ${y("'false'")}."
                )
            ),
            examples = Seq(
                Example(
                    usage = Seq("> gen-project -n=MyProject -l=scala -b=sbt"),
                    desc = s"Generates Scala SBT project."
                ),
                Example(
                    usage = Seq("> gen-project -n=MyProject -l=kotlin -p=com.mycompany.nlp -o=true"),
                    desc = s"Generates Kotlin Maven project."
                )
            )
        ),
        Command(
            name = "gen-model",
            group = "4. Miscellaneous Tools",
            synopsis = s"Generates data model file stub.",
            desc = Some(
                "Generated model stub will have all default configuration. Model file can be either YAML or JSON."
            ),
            body = cmdGenModel,
            params = Seq(
                Parameter(
                    id = "filePath",
                    names = Seq("--filePath", "-f"),
                    value = Some("path"),
                    desc =
                        s"File path for the model stub. File path can either be an absolute path, relative path or " +
                        s"just a file name in which case the current folder will be used. File must have one of the " +
                        s"following extensions: ${y("'json'")}, ${y("'js'")}, ${y("'yaml'")}, or ${y("'yml'")}."
                ),
                Parameter(
                    id = "modelId",
                    names = Seq("--modelId", "-n"),
                    value = Some("id"),
                    desc = "Model ID."
                ),
                Parameter(
                    id = "override",
                    names = Seq("--override", "-o"),
                    value = Some("true|false"),
                    optional = true,
                    desc = s"Override output directory flag. Supported: ${y("'true'")}, ${y("'false'")}. Default value is ${y("'false'")}"
                )
            ),
            examples = Seq(
                Example(
                    usage = Seq("> gen-model -f=myModel.json -n=my.model.id"),
                    desc = s"Generates JSON model file stub in the current folder."
                ),
                Example(
                    usage = Seq("> gen-model -f=c:/tmp/myModel.yaml -n=my.model.id --override=true"),
                    desc = s"Generates YAML model file stub in ${y("'c:/temp'")} folder overriding existing file, if any."
                )
            )
        )
    ).sortBy(_.name)

    require(
        U.getDups(CMDS.map(_.name)).isEmpty,
        "Dup commands."
    )

    private final val NO_ANSI_CMD = CMDS.find(_.name == "no-ansi").get
    private final val ANSI_CMD = CMDS.find(_.name == "ansi").get
    private final val QUIT_CMD = CMDS.find(_.name == "quit").get
    private final val HELP_CMD = CMDS.find(_.name == "help").get
    private final val REST_CMD = CMDS.find(_.name == "rest").get
    private final val CALL_CMD = CMDS.find(_.name == "call").get
    private final val ASK_CMD = CMDS.find(_.name == "ask").get
    private final val SUGSYN_CMD = CMDS.find(_.name == "sugsyn").get
    private final val STOP_SRV_CMD = CMDS.find(_.name == "stop-server").get
    private final val START_SRV_CMD = CMDS.find(_.name == "start-server").get

    /**
     *
     * @param s
     * @return
     */
    private def stripQuotes(s: String): String = {
        var x = s
        var found = true

        while (found) {
            found = false

            if (x.startsWith("\"") && x.endsWith("\"")) {
                found = true

                x = x.substring(1, x.length - 1)
            }

            if (x.startsWith("'") && x.endsWith("'")) {
                found = true

                x = x.substring(1, x.length - 1)
            }
        }

        x
    }

    /**
     *
     * @param endpoint
     * @return
     */
    private def restHealth(endpoint: String): Int =
        httpGet(endpoint, "health", mkHttpHandler(_.getStatusLine.getStatusCode))

    /**
     *
     * @param pathOpt
     */
    private def checkFilePath(pathOpt: Option[Argument]): Unit = {
        if (pathOpt.isDefined) {
            val file = new File(stripQuotes(pathOpt.get.value.get))

            if (!file.exists() || !file.isFile)
                throw new IllegalArgumentException(s"File not found: ${c(file.getAbsolutePath)}")
        }
    }

    /**
     * @param cmd  Command descriptor.
     * @param args Arguments, if any, for this command.
     * @param repl Whether or not running from REPL.
     */
    private def cmdStartServer(cmd: Command, args: Seq[Argument], repl: Boolean): Unit = {
        val cfgPath = args.find(_.parameter.id == "config")
        val igniteCfgPath = args.find(_.parameter.id == "igniteConfig")
        val noWait = args.exists(_.parameter.id == "noWait")

        checkFilePath(cfgPath)
        checkFilePath(igniteCfgPath)

        // Ensure that there isn't another local server running.
        loadServerBeacon() match {
            case Some(b) ⇒ throw new IllegalStateException(s"Existing server (pid ${c(b.pid)}) detected.")
            case None ⇒ ()
        }

        val logTstamp = currentTime

        // Server log redirect.
        val output = new File(SystemUtils.getUserHome, s".nlpcraft/server_log_$logTstamp.txt")

        // Store in REPL state right away.
        state.serverLog = Some(output)

        val srvPb = new ProcessBuilder(
            JAVA,
            "-ea",
            "-Xms2048m",
            "-XX:+UseG1GC",
            // Required by Ignite 2.x running on JDK 11+.
            "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
            "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED",
            "--add-exports=java.management/com.sun.jmx.mbeanserver=ALL-UNNAMED",
            "--add-exports=jdk.internal.jvmstat/sun.jvmstat.monitor=ALL-UNNAMED",
            "--add-exports=java.base/sun.reflect.generics.reflectiveObjects=ALL-UNNAMED",
            "--add-opens=jdk.management/com.sun.management.internal=ALL-UNNAMED",
            "--illegal-access=permit",
            "-DNLPCRAFT_ANSI_COLOR_DISABLED=true", // No ANSI colors for text log output to the file.
            "-cp",
            s"$JAVA_CP",
            "org.apache.nlpcraft.NCStart",
            "-server",
            cfgPath match {
                case Some(path) ⇒ s"-config=${stripQuotes(path.value.get)}"
                case None ⇒ ""
            },
            igniteCfgPath match {
                case Some(path) ⇒ s"-igniteConfig=${stripQuotes(path.value.get)}"
                case None ⇒ ""
            },
        )

        srvPb.directory(new File(INSTALL_HOME))
        srvPb.redirectErrorStream(true)

        val bleachPb = new ProcessBuilder(
            JAVA,
            "-ea",
            "-cp",
            s"$JAVA_CP",
            "org.apache.nlpcraft.model.tools.cmdline.NCCliAnsiBleach"
        )

        bleachPb.directory(new File(INSTALL_HOME))
        bleachPb.redirectOutput(Redirect.appendTo(output))

        try {
            // Start the 'server | bleach > server log output' process pipeline.
            val procs = ProcessBuilder.startPipeline(Seq(srvPb, bleachPb).asJava)

            val srvPid = procs.get(0).pid()

            // Store mapping file between PID and timestamp (once we have server PID).
            // Note that the same timestamp is used in server log file.
            ignoring(classOf[IOException]) {
                new File(SystemUtils.getUserHome, s".nlpcraft/.pid_${srvPid}_tstamp_$logTstamp").createNewFile()
            }

            logln(s"Server output > ${c(output.getAbsolutePath)}")

            /**
             *
             */
            def showTip(): Unit = {
                val tbl = new NCAsciiTable()

                tbl += (s"${g("stop-server")}", "Stop the server.")
                tbl += (s"${g("info-server")}", "Get server information.")
                tbl += (s"${g("restart-server")}", "Restart the server.")
                tbl += (s"${g("ping-server")}", "Ping the server.")
                tbl += (s"${g("tail-server")}", "Tail the server log.")

                logln(s"Handy commands:\n${tbl.toString}")
            }

            if (noWait) {
                logln(s"Server is starting...")

                showTip()
            }
            else {
                val progressBar = new NCAnsiProgressBar(
                    term.writer(),
                    NUM_SRV_SERVICES,
                    15,
                    true,
                    // ANSI is NOT disabled & we ARE NOT running from IDEA or Eclipse...
                    NCAnsi.isEnabled && IS_SCRIPT
                )

                log(s"Server is starting ")

                progressBar.start()

                // Tick progress bar "almost" right away to indicate the progress start.
                new Thread(() => {
                    Thread.sleep(1.secs)

                    progressBar.ticked()
                })
                .start()

                val tailer = Tailer.create(
                    state.serverLog.get,
                    new TailerListenerAdapter {
                        override def handle(line: String): Unit = {
                            if (TAILER_PTRN.matcher(line).matches())
                                progressBar.ticked()
                        }
                    },
                    500.ms
                )

                var beacon: NCCliServerBeacon = null
                var online = false
                val endOfWait = currentTime + 3.mins // We try for 3 mins max.

                while (currentTime < endOfWait && !online) {
                    if (progressBar.completed) {
                        // First, load the beacon, if any.
                        if (beacon == null)
                            beacon = loadServerBeacon(autoSignIn = true).orNull

                        // Once beacon is loaded, ensure that REST endpoint is live.
                        if (beacon != null)
                            online = Try(restHealth("http://" + beacon.restEndpoint) == 200).getOrElse(false)
                    }

                    if (!online)
                        Thread.sleep(2.secs) // Check every 2 secs.
                }

                tailer.stop()

                progressBar.stop()

                if (!online) {
                    logln(r(" [Error]"))
                    error(s"Timed out starting server, check output for errors.")
                }
                else {
                    logln(g(" [OK]"))
                    logServerInfo(beacon)

                    showTip()

                    if (state.accessToken.isDefined)
                        logln(s"Signed in with default '${c("admin@admin.com")}' user.")
                }
            }
        }
        catch {
            case e: Exception ⇒ error(s"Server failed to start: ${y(e.getLocalizedMessage)}")
        }
    }

    /**
     *
     * @return
     */
    private def getRestEndpointFromBeacon: String =
        loadServerBeacon() match {
            case Some(beacon) ⇒ s"http://${beacon.restEndpoint}"
            case None ⇒ throw NoLocalServer()
        }

    /**
     * @param cmd  Command descriptor.
     * @param args Arguments, if any, for this command.
     * @param repl Whether or not executing from REPL.
     */
    private def cmdTailServer(cmd: Command, args: Seq[Argument], repl: Boolean): Unit = {
        val lines = args.find(_.parameter.id == "lines") match {
            case Some(arg) ⇒
                try
                    Integer.parseInt(arg.value.get)
                catch {
                    case _: Exception ⇒ throw InvalidParameter(cmd, "lines")
                }

            case None ⇒ 20 // Default.
        }

        if (lines <= 0)
            throw InvalidParameter(cmd, "lines")

        loadServerBeacon() match {
            case Some(beacon) ⇒
                try
                    managed(new ReversedLinesFileReader(new File(beacon.logPath), StandardCharsets.UTF_8)) acquireAndGet { in ⇒
                        var tail = List.empty[String]

                        breakable {
                            for (_ ← 0 until lines)
                                in.readLine() match {
                                    case null ⇒ break
                                    case line ⇒ tail ::= line
                                }
                        }

                        val cnt = tail.size

                        logln(bb(w(s"+----< ${K}Last $cnt server log lines $W>---")))
                        tail.foreach(line ⇒ logln(s"${bb(w("| "))}  $line"))
                        logln(bb(w(s"+----< ${K}Last $cnt server log lines $W>---")))
                    }
                catch {
                    case e: Exception ⇒ error(s"Failed to read log file: ${e.getLocalizedMessage}")
                }

            case None ⇒ throw NoLocalServer()
        }
    }

    /**
     * @param cmd  Command descriptor.
     * @param args Arguments, if any, for this command.
     * @param repl Whether or not executing from REPL.
     */
    private def cmdPingServer(cmd: Command, args: Seq[Argument], repl: Boolean): Unit = {
        val endpoint = getRestEndpointFromBeacon

        val num = args.find(_.parameter.id == "number") match {
            case Some(arg) ⇒
                try
                    Integer.parseInt(arg.value.get)
                catch {
                    case _: Exception ⇒ throw InvalidParameter(cmd, "number")
                }

            case None ⇒ 1 // Default.
        }

        var i = 0

        while (i < num) {
            log(s"(${i + 1} of $num) pinging REST server at ${b(endpoint)} ")

            val spinner = new NCAnsiSpinner(
                term.writer(),
                // ANSI is NOT disabled & we ARE NOT running from IDEA or Eclipse...
                NCAnsi.isEnabled && IS_SCRIPT
            )

            spinner.start()

            val startMs = currentTime

            try
                restHealth(endpoint) match {
                    case 200 ⇒
                        spinner.stop()

                        logln(g("OK") + " " + c(s"[${currentTime - startMs}ms]"))

                    case code: Int ⇒
                        spinner.stop()

                        logln(r("FAIL") + s" [HTTP ${y(code.toString)}]")
                }
            catch {
                case _: SSLException ⇒
                    spinner.stop()

                    logln(r("FAIL") + s" ${y("[SSL error]")}")

                case _: IOException ⇒
                    spinner.stop()

                    logln(r("FAIL") + s" ${y("[I/O error]")}")
            }

            i += 1

            if (i < num)
            // Pause between pings.
                Thread.sleep(500.ms)
        }
    }

    /**
     * Loads and returns server beacon file.
     *
     * @param autoSignIn
     * @return
     */
    private def loadServerBeacon(autoSignIn: Boolean = false): Option[NCCliServerBeacon] = {
        val beaconOpt = try {
            val beacon = (
                managed(
                    new ObjectInputStream(
                        new FileInputStream(
                            new File(SystemUtils.getUserHome, SRV_BEACON_PATH)
                        )
                    )
                ) acquireAndGet {
                    _.readObject()
                }
            )
            .asInstanceOf[NCCliServerBeacon]

            ProcessHandle.of(beacon.pid).asScala match {
                case Some(ph) ⇒
                    beacon.ph = ph

                    // See if we can detect server log if server was started by this script.
                    val files = new File(SystemUtils.getUserHome, ".nlpcraft").listFiles(new FilenameFilter {
                        override def accept(dir: File, name: String): Boolean =
                            name.startsWith(s".pid_$ph")
                    })

                    if (files.size == 1) {
                        val split = files(0).getName.split("_")

                        if (split.size == 4) {
                            val logFile = new File(SystemUtils.getUserHome, s".nlpcraft/server_log_${split(3)}.txt")

                            if (logFile.exists())
                                beacon.logPath = logFile.getAbsolutePath
                        }
                    }

                    Some(beacon)
                case None ⇒
                    // Attempt to clean up stale beacon file.
                    new File(SystemUtils.getUserHome, SRV_BEACON_PATH).delete()

                    None
            }
        }
        catch {
            case _: Exception ⇒ None
        }

        beaconOpt match {
            case Some(beacon) ⇒
                state.isServerOnline = true

                val baseUrl = "http://" + beacon.restEndpoint

                try {
                    // Attempt to signin with the default account.
                    if (autoSignIn && state.accessToken.isEmpty)
                        httpPostResponseJson(
                            baseUrl,
                            "signin",
                            "{\"email\": \"admin@admin.com\", \"passwd\": \"admin\"}") match {
                            case Some(json) ⇒ state.accessToken = Option(Try(U.getJsonStringField(json, "acsTok")).getOrElse(null))
                            case None ⇒ ()
                        }

                    // Attempt to get all connected probes if successfully signed in prior.
                    if (state.accessToken.isDefined)
                        httpPostResponseJson(
                            baseUrl,
                            "probe/all",
                            "{\"acsTok\": \"" + state.accessToken.get + "\"}") match {
                            case Some(json) ⇒ state.probes =
                                Try(
                                    U.jsonToObject[ProbeAllResponse](json, classOf[ProbeAllResponse]).probes.toList
                                ).getOrElse(Nil)
                            case None ⇒ ()
                        }
                }
                catch {
                    case _: Exception ⇒
                        // Reset REPL state.
                        state = ReplState()
                }

            case None ⇒
                // Reset REPL state.
                state = ReplState()
        }

        beaconOpt
    }

    /**
     * @param cmd  Command descriptor.
     * @param args Arguments, if any, for this command.
     * @param repl Whether or not executing from REPL.
     */
    private def cmdQuit(cmd: Command, args: Seq[Argument], repl: Boolean): Unit = {
        // No-op.
    }

    /**
     * @param cmd  Command descriptor.
     * @param args Arguments, if any, for this command.
     * @param repl Whether or not executing from REPL.
     */
    private def cmdRestartServer(cmd: Command, args: Seq[Argument], repl: Boolean): Unit = {
        if (loadServerBeacon().isDefined)
            STOP_SRV_CMD.body(STOP_SRV_CMD, Seq.empty, repl)

        START_SRV_CMD.body(START_SRV_CMD, args, repl)
    }

    /**
     * @param cmd  Command descriptor.
     * @param args Arguments, if any, for this command.
     * @param repl Whether or not executing from REPL.
     */
    private def cmdStopServer(cmd: Command, args: Seq[Argument], repl: Boolean): Unit =
        loadServerBeacon() match {
            case Some(beacon) ⇒
                val pid = beacon.pid

                // TODO: signout if previously signed in.

                if (beacon.ph.destroy()) {
                    logln(s"Server (pid ${c(pid)}) has been stopped.")

                    // Attempt to delete beacon file right away.
                    new File(beacon.beaconPath).delete()

                    // Reset REPL state right away.
                    state = ReplState()
                }
                else
                    error(s"Failed to stop the local REST server (pid ${c(pid)}).")

            case None ⇒ throw NoLocalServer()
        }

    /**
     * @param cmd  Command descriptor.
     * @param args Arguments, if any, for this command.
     * @param repl Whether or not executing from REPL.
     */
    private def cmdNoAnsi(cmd: Command, args: Seq[Argument], repl: Boolean): Unit =
        NCAnsi.setEnabled(false)

    /**
     * @param cmd  Command descriptor.
     * @param args Arguments, if any, for this command.
     * @param repl Whether or not executing from REPL.
     */
    private def cmdAnsi(cmd: Command, args: Seq[Argument], repl: Boolean): Unit =
        NCAnsi.setEnabled(true)

    /**
     * @param cmd  Command descriptor.
     * @param args Arguments, if any, for this command.
     * @param repl Whether or not executing from REPL.
     */
    private def cmdHelp(cmd: Command, args: Seq[Argument], repl: Boolean): Unit = {
        /**
         *
         */
        def header(): Unit = logln(
            s"""|${ansiBold("NAME")}
                |$T___${y(s"'$SCRIPT_NAME'")} - command line interface to control NLPCraft.
                |
                |${ansiBold("USAGE")}
                |$T___${y(s"'$SCRIPT_NAME'")} [COMMAND] [PARAMETERS]
                |
                |${T___}Without any arguments the script starts in REPL mode. The REPL mode supports all
                |${T___}the same commands as command line mode. In REPL mode you need to put values that
                |${T___}can have spaces (like JSON or file paths) inside of single or double quotes both
                |${T___}of which can be escaped using '\\' character.
                |
                |${ansiBold("COMMANDS")}""".stripMargin
        )

        /**
         *
         * @param cmd
         * @return
         */
        def mkCmdLines(cmd: Command): Seq[String] = {
            var lines = mutable.Buffer.empty[String]

            if (cmd.desc.isDefined)
                lines += cmd.synopsis + " " + cmd.desc.get
            else
                lines += cmd.synopsis

            if (cmd.params.nonEmpty) {
                lines += ""
                lines += ansiBold("PARAMETERS")

                for (param ← cmd.params) {
                    val line =
                        if (param.value.isDefined)
                            T___ + param.names.zip(Stream.continually(param.value.get)).map(t ⇒ s"${t._1}=${t._2}").mkString(", ")
                        else
                            s"$T___${param.names.mkString(",")}"

                    lines += c(line)

                    if (param.optional)
                        lines += s"$T___${T___}Optional."

                    lines += s"$T___$T___${param.desc}"
                    lines += ""
                }

                lines.remove(lines.size - 1) // Remove last empty line.
            }

            if (cmd.examples.nonEmpty) {
                lines += ""
                lines += ansiBold("EXAMPLES")

                for (ex ← cmd.examples) {
                    lines ++= ex.usage.map(s ⇒ y(s"$T___$s"))
                    lines += s"$T___$T___${ex.desc}"
                }
            }

            lines
        }

        def helpHelp(): Unit =
            logln(s"" +
                s"\n" +
                s"Type ${c("help --cmd=xxx")} to get help for ${c("xxx")} command.\n" +
                s"\n" +
                s"You can also execute any OS specific command by prepending '${c("$")}' in front of it:\n" +
                s"  ${y("> $cmd /c dir")}\n" +
                s"    Run Windows ${c("dir")} command in a separate shell.\n" +
                s"  ${y("> $ls -la")}\n" +
                s"    Run Linux/Unix ${c("ls -la")} command.\n"
            )

        if (args.isEmpty) { // Default - show abbreviated help.
            if (!repl)
                header()

            CMDS.groupBy(_.group).toSeq.sortBy(_._1).foreach(entry ⇒ {
                val grp = entry._1
                val grpCmds = entry._2

                val tbl = NCAsciiTable().margin(left = if (repl) 0 else 4)

                grpCmds.sortBy(_.name).foreach(cmd ⇒ tbl +/ (
                    "" → s"${g(cmd.name)}",
                    "align:left, maxWidth:85" → cmd.synopsis
                ))

                logln(s"\n$B$grp:$RST\n${tbl.toString}")
            })

            helpHelp()
        }
        else if (args.size == 1 && args.head.parameter.id == "all") { // Show a full format help for all commands.
            if (!repl)
                header()

            val tbl = NCAsciiTable().margin(left = if (repl) 0 else 4)

            CMDS.foreach(cmd ⇒
                tbl +/ (
                    "" → s"${g(cmd.name)}",
                    "align:left, maxWidth:85" → mkCmdLines(cmd)
                )
            )

            logln(tbl.toString)

            helpHelp()
        }
        else { // Help for individual commands.
            var err = false
            val seen = mutable.Buffer.empty[String]

            val tbl = NCAsciiTable().margin(left = if (repl) 0 else 4)

            for (arg ← args) {
                val cmdName = arg.value.get

                CMDS.find(_.name == cmdName) match {
                    case Some(c) ⇒
                        if (!seen.contains(c.name)) {
                            tbl +/ (
                                "" → s"${g(c.name)}",
                                "align:left, maxWidth:85" → mkCmdLines(c)
                            )

                            seen += c.name
                        }
                    case None ⇒
                        err = true

                        errorUnknownCommand(cmdName)
                }
            }

            if (!err) {
                if (!repl)
                    header()

                logln(tbl.toString)
            }
        }
    }

    /**
     *
     * @param beacon
     * @return
     */
    private def logServerInfo(beacon: NCCliServerBeacon): Unit = {
        var tbl = new NCAsciiTable

        val logPath = if (beacon.logPath != null) g(beacon.logPath) else y("<not available>")

        tbl += ("PID", s"${g(beacon.pid)}")
        tbl += ("Database:", "")
        tbl += ("  URL", s"${g(beacon.dbUrl)}")
        tbl += ("  Driver", s"${g(beacon.dbDriver)}")
        tbl += ("  Pool min", s"${g(beacon.dbPoolMin)}")
        tbl += ("  Pool init", s"${g(beacon.dbPoolInit)}")
        tbl += ("  Pool max", s"${g(beacon.dbPoolMax)}")
        tbl += ("  Pool increment", s"${g(beacon.dbPoolInc)}")
        tbl += ("  Reset on start", s"${g(beacon.dbInit)}")
        tbl += ("REST:", "")
        tbl += ("  Endpoint", s"${g(beacon.restEndpoint)}")
        tbl += ("  API provider", s"${g(beacon.restApi)}")
        tbl += ("Probe:", "")
        tbl += ("  Uplink", s"${g(beacon.upLink)}")
        tbl += ("  Downlink", s"${g(beacon.downLink)}")
        tbl += ("Token providers", s"${g(beacon.tokenProviders)}")
        tbl += ("NLP engine", s"${g(beacon.nlpEngine)}")
        tbl += ("Access tokens:", "")
        tbl += ("  Scan frequency", s"$G${beacon.acsToksScanMins} mins$RST")
        tbl += ("  Expiration timeout", s"$G${beacon.acsToksExpireMins} mins$RST")
        tbl += ("External config:", "")
        tbl += ("  URL", s"${g(beacon.extConfigUrl)}")
        tbl += ("  Check MD5", s"${g(beacon.extConfigCheckMd5)}")
        tbl += ("Log file", logPath)
        tbl += ("Started on", s"${g(DateFormat.getDateTimeInstance.format(new Date(beacon.startMs)))}")

        logln(s"Local REST server:\n${tbl.toString}")

        tbl = new NCAsciiTable

        def addProbeToTable(tbl: NCAsciiTable, probe: Probe): NCAsciiTable = {
            tbl += (
                Seq(
                    probe.probeId,
                    s"  ${c("guid")}: ${probe.probeGuid}",
                    s"  ${c("tok")}: ${probe.probeToken}"
                ),
                DurationFormatUtils.formatDurationHMS(currentTime - probe.startTstamp),
                Seq(
                    s"${probe.hostName} (${probe.hostAddr})",
                    s"${probe.osName} ver. ${probe.osVersion}"
                ),
                probe.models.toList.map(m ⇒ s"${b(m.id)}, v${m.version}")
            )

            tbl
        }

        tbl #= (
            "Probe ID",
            "Uptime",
            "Host / OS",
            "Models Deployed"
        )

        state.probes.foreach(addProbeToTable(tbl, _))

        logln(s"Connected probes (${state.probes.size}):\n${tbl.toString}")
    }

    /**
     *
     * @param cmd  Command descriptor.
     * @param args Arguments, if any, for this command.
     * @param repl Whether or not executing from REPL.
     */
    private def cmdInfoServer(cmd: Command, args: Seq[Argument], repl: Boolean): Unit = {
        loadServerBeacon() match {
            case Some(beacon) ⇒ logServerInfo(beacon)
            case None ⇒ throw NoLocalServer()
        }
    }

    /**
     *
     * @param cmd  Command descriptor.
     * @param args Arguments, if any, for this command.
     * @param repl Whether or not executing from REPL.
     */
    private def cmdCls(cmd: Command, args: Seq[Argument], repl: Boolean): Unit =
        term.puts(Capability.clear_screen)

    /**
     *
     * @param body
     * @return
     */
    private def mkHttpHandler[T](body: HttpResponse ⇒ T): ResponseHandler[T] =
        (resp: HttpResponse) => body(resp)

    /**
     *
     * @param cmd  Command descriptor.
     * @param args Arguments, if any, for this command.
     * @param repl Whether or not executing from REPL.
     */
    private def cmdSignIn(cmd: Command, args: Seq[Argument], repl: Boolean): Unit =
        state.accessToken match {
            case None ⇒
                val email = args.find(_.parameter.id == "email").flatMap(_.value).getOrElse("admin@admin.com")
                val passwd = args.find(_.parameter.id == "passwd").flatMap(_.value).getOrElse("admin")

                httpRest(
                    cmd,
                    "signin",
                    s"""
                       |{
                       |    "email": ${jsonQuote(email)},
                       |    "passwd": ${jsonQuote(passwd)}
                       |}
                       |""".stripMargin
                )

            case Some(_) ⇒ error(s"Already signed in. See ${c("'signout'")} command.")
        }

    /**
     *
     * @param cmd  Command descriptor.
     * @param args Arguments, if any, for this command.
     * @param repl Whether or not executing from REPL.
     */
    private def cmdSignOut(cmd: Command, args: Seq[Argument], repl: Boolean): Unit =
        state.accessToken match {
            case Some(acsTok) ⇒
                httpRest(
                    cmd,
                    "signout",
                    s"""
                       |{"acsTok": ${jsonQuote(acsTok)}}
                       |""".stripMargin
                )

            case None ⇒ error(s"Not signed in. See ${c("'signin'")} command.")
        }

    /**
     * Quotes given string in double quotes unless it is already quoted as such.
     *
     * @param s
     * @return
     */
    private def jsonQuote(s: String): String = {
        if (s == null)
            null
        else {
            val ss = s.trim()

            if (ss.startsWith("\"") && ss.endsWith("\""))
                ss
            else
                s""""$ss""""
        }
    }

    /**
     *
     * @param cmd  Command descriptor.
     * @param args Arguments, if any, for this command.
     * @param repl Whether or not executing from REPL.
     */
    private def cmdSqlGen(cmd: Command, args: Seq[Argument], repl: Boolean): Unit = {
        val nativeArgs = args.flatMap { arg ⇒
            val param = arg.parameter.names.head

            arg.value match {
                case None ⇒ Seq(param)
                case Some(v) ⇒ Seq(param, v)
            }
        }

        try
            NCSqlModelGeneratorImpl.process(repl = true, nativeArgs.toArray)
        catch {
            case e: Exception ⇒ error(e.getLocalizedMessage)
        }
    }

    /**
     *
     * @param cmd Command descriptor.
     * @param args Arguments, if any, for this command.
     * @param repl Whether or not executing from REPL.
     */
    private def cmdSugSyn(cmd: Command, args: Seq[Argument], repl: Boolean): Unit =
        state.accessToken match {
            case Some(acsTok) ⇒
                val mdlId = args.find(_.parameter.id == "mdlId").flatMap(_.value).getOrElse(throw MissingParameter(cmd, "mdlId"))
                val minScore = Try(args.find(_.parameter.id == "minScore").flatMap(_.value).getOrElse("0.5").toFloat).getOrElse(throw InvalidParameter(cmd, "minScore"))

                httpRest(
                    cmd,
                    "model/sugsyn",
                    s"""
                       |{
                       |    "acsTok": ${jsonQuote(acsTok)},
                       |    "mdlId": ${jsonQuote(mdlId)},
                       |    "minScore": $minScore
                       |}
                       |""".stripMargin
                )

            case None ⇒ error(s"Not signed in. See ${c("'signin'")} command.")
        }

    /**
     *
     * @param cmd Command descriptor.
     * @param args Arguments, if any, for this command.
     * @param repl Whether or not executing from REPL.
     */
    private def cmdAsk(cmd: Command, args: Seq[Argument], repl: Boolean): Unit =
        state.accessToken match {
            case Some(acsTok) ⇒
                val mdlId = args.find(_.parameter.id == "mdlId").flatMap(_.value).getOrElse(throw MissingParameter(cmd, "mdlId"))
                val txt = args.find(_.parameter.id == "txt").flatMap(_.value).getOrElse(throw MissingParameter(cmd, "txt"))
                val data = args.find(_.parameter.id == "data").flatMap(_.value).orNull
                val enableLog = args.find(_.parameter.id == "enableLog").flatMap(_.value).getOrElse(false)

                httpRest(
                    cmd,
                    "ask/sync",
                    s"""
                       |{
                       |    "acsTok": ${jsonQuote(acsTok)},
                       |    "mdlId": ${jsonQuote(mdlId)},
                       |    "txt": ${jsonQuote(txt)},
                       |    "data": ${jsonQuote(data)},
                       |    "enableLog": $enableLog
                       |}
                       |""".stripMargin
                )

            case None ⇒ error(s"Not signed in. See ${c("'signin'")} command.")
        }

    /**
     *
     * @param cmd Command descriptor.
     * @param args Arguments, if any, for this command.
     * @param repl Whether or not executing from REPL.
     */
    private def cmdCall(cmd: Command, args: Seq[Argument], repl: Boolean): Unit = {
        val normArgs = args.filter(!_.parameter.synthetic)
        val synthArgs = args.filter(_.parameter.synthetic)

        val path = normArgs.find(_.parameter.id == "path").getOrElse(throw MissingParameter(cmd, "path")).value.get

        var first = true
        val buf = new StringBuilder()

        val spec = REST_SPEC.find(_.path == path).getOrElse(throw InvalidParameter(cmd, "path"))

        var mandatoryParams = spec.params.filter(!_.optional)

        for (arg ← synthArgs) {
            val jsName = arg.parameter.id

            spec.params.find(_.name == jsName) match {
                case Some(param) ⇒
                    mandatoryParams = mandatoryParams.filter(_.name != jsName)

                    if (!first)
                        buf ++= ","

                    first = false

                    buf ++= "\"" + jsName + "\":"

                    val value = arg.value.getOrElse(throw InvalidJsonParameter(cmd, arg.parameter.names.head))

                    param.kind match {
                        case STRING ⇒ buf ++= "\"" + U.escapeJson(stripQuotes(value)) + "\""
                        case OBJECT | ARRAY ⇒ buf ++= stripQuotes(value)
                        case BOOLEAN | NUMERIC ⇒ buf ++= value
                    }

                case None ⇒ throw InvalidJsonParameter(cmd, jsName)
            }
        }

        if (mandatoryParams.nonEmpty)
            throw MissingMandatoryJsonParameters(cmd, mandatoryParams, path)

        httpRest(cmd, path, s"{${buf.toString()}}")
    }

    /**
     *
     * @param cmd Command descriptor.
     * @param args Arguments, if any, for this command.
     * @param repl Whether or not executing from REPL.
     */
    private def cmdRest(cmd: Command, args: Seq[Argument], repl: Boolean): Unit = {
        val path = args.find(_.parameter.id == "path").getOrElse(throw MissingParameter(cmd, "path")).value.get
        val json = stripQuotes(args.find(_.parameter.id == "json").getOrElse(throw MissingParameter(cmd, "json")).value.get)

        httpRest(cmd, path, json)
    }

    /**
      * @param cmd
      * @param args
      * @param id
      * @param dflt
      */
    @throws[MissingParameter]
    private def get(cmd: Command, args: Seq[Argument], id: String, dflt: String = null): String =
        args.find(_.parameter.id == id).flatMap(_.value) match {
            case Some(v) ⇒ v
            case None ⇒
                if (dflt == null)
                    throw MissingParameter(cmd, id)

                dflt
        }

    /**
      *
      * @param cmd
      * @param name
      * @param value
      * @param supported
      */
    @throws[InvalidParameter]
    private def checkSupported(cmd: Command, name: String, value: String, supported: String*): Unit =
        if (!supported.contains(value))
            throw InvalidParameter(cmd, name)

    /**
      *
      * @param lines
      * @param cmtBegin Comment begin sequence.
      * @param cmtEnd Comment end sequence.
      */
    private def extractHeader0(lines: Seq[String], cmtBegin: String, cmtEnd: String): (Int, Int) = {
        var startIdx, endIdx = -1

        for ((line, idx) ← lines.zipWithIndex if startIdx == -1 || endIdx == -1) {
            val t = line.trim

            if (t == cmtBegin) {
                if (startIdx == -1)
                    startIdx = idx
            }
            else if (t == cmtEnd) {
                if (startIdx != -1 && endIdx == -1)
                    endIdx = idx
            }
        }

        if (startIdx == -1) (-1, -1) else (startIdx, endIdx)
    }

    /**
      *
      * @param lines
      * @param cmtBegin One-line comment begin sequence.
      */
    private def extractHeader0(lines: Seq[String], cmtBegin: String = "#"): (Int, Int) = {
        var startIdx, endIdx = -1

        for ((line, idx) ← lines.zipWithIndex if startIdx == -1 || endIdx == -1)
            if (line.trim.startsWith(cmtBegin)) {
                if (startIdx == -1)
                    startIdx = idx
            }
            else {
                if (startIdx != -1 && endIdx == -1) {
                    require(idx > 0)

                    endIdx = idx - 1
                }
            }

        if (startIdx == -1)
            (-1, -1)
        else if (endIdx == -1)
            (startIdx, lines.size - 1)
        else
            (startIdx, endIdx)
    }

    def extractJavaHeader(lines: Seq[String]): (Int, Int) = extractHeader0(lines, "/*", "*/")
    def extractJsonHeader(lines: Seq[String]): (Int, Int) = extractHeader0(lines, "/*", "*/")
    def extractGradleHeader(lines: Seq[String]): (Int, Int) = extractHeader0(lines, "/*", "*/")
    def extractSbtHeader(lines: Seq[String]): (Int, Int) = extractHeader0(lines, "/*", "*/")
    def extractXmlHeader(lines: Seq[String]): (Int, Int) = extractHeader0(lines, "<!--", "-->")
    def extractYamlHeader(lines: Seq[String]): (Int, Int) = extractHeader0(lines)
    def extractPropertiesHeader(lines: Seq[String]): (Int, Int) = extractHeader0(lines)

    /**
      *
      * @param zipInDir
      * @param dst
      * @param inEntry
      * @param outEntry
      * @param repls
      */
    @throws[NCE]
    private def copy(
        zipInDir: String,
        dst: File,
        inEntry: String,
        outEntry: String,
        extractHeader: Option[Seq[String] ⇒ (Int, Int)],
        repls: (String, String)*
    ) {
        val key = s"$zipInDir/$inEntry"

        require(PRJ_TEMPLATES.contains(key), s"Unexpected template entry for: $key")

        var lines = PRJ_TEMPLATES(key)

        val outFile = if (dst != null) new File(dst, outEntry) else new File(outEntry)
        val parent = outFile.getAbsoluteFile.getParentFile

        if (parent == null || !parent.exists() && !parent.mkdirs())
            throw new NCE(s"Invalid folder: ${parent.getAbsolutePath}")

        // Drops headers.
        extractHeader match {
            case Some(ext) ⇒
                val (hdrFrom, hdrTo) = ext(lines)

                lines = lines.zipWithIndex.flatMap {
                    case (line, idx) ⇒ if (idx < hdrFrom || idx > hdrTo) Some(line) else None
                }
            case None ⇒ // No-op.
        }

        // Drops empty line in begin and end of the file.
        lines = lines.dropWhile(_.trim.isEmpty).reverse.dropWhile(_.trim.isEmpty).reverse

        val buf = mutable.ArrayBuffer.empty[(String, String)]

        for (line ← lines) {
            val t = line.trim

            // Drops duplicated empty lines, which can be appeared because header deleting.
            if (buf.isEmpty || t.nonEmpty || t != buf.last._2)
                buf += (line → t)
        }

        var cont = buf.map(_._1).mkString("\n")

        cont = repls.foldLeft(cont)((s, repl) ⇒ s.replaceAll(repl._1, repl._2))

        try
            managed(new FileWriter(outFile)) acquireAndGet { w ⇒
                managed(new BufferedWriter(w)) acquireAndGet { bw ⇒
                    bw.write(cont)
                }
            }
        catch {
            case e: IOException ⇒ throw new NCE(s"Error writing $outEntry", e)
        }
    }

    /**
      *
      * @param cmd Command descriptor.
      * @param args Arguments, if any, for this command.
      * @param repl Whether or not executing from REPL.
      */
    private def cmdGenModel(cmd: Command, args: Seq[Argument], repl: Boolean): Unit = {
        val filePath = get(cmd, args, "filePath")
        val overrideFlag = get(cmd, args,"override", "false").toLowerCase
        val modelId = get(cmd, args,"modelId")

        checkSupported(cmd,"overrideFlag", overrideFlag, "true", "false")

        val out = new File(filePath)

        if (out.isDirectory)
            throw new NCE(s"Invalid file path: ${c(out.getAbsolutePath)}")

        if (out.exists()) {
            if (overrideFlag == "true") {
                if (!out.delete())
                    throw new NCE(s"Couldn't delete file: ${c(out.getAbsolutePath)}")
            }
            else
                throw new NCE(s"File already exists: ${c(out.getAbsolutePath)}")
        }

        val (fileExt, extractHdr) = {
            val lc = filePath.toLowerCase

            if (lc.endsWith(".yaml") || lc.endsWith(".yml"))
                ("yaml", extractYamlHeader _)
            else if (lc.endsWith(".json") || lc.endsWith(".js"))
                ("json", extractJsonHeader _)
            else
                throw new NCE(s"Unsupported model file type (extension): ${c(filePath)}")
        }

        copy(
            "nlpcraft-java-mvn",
            out.getParentFile,
            s"src/main/resources/template_model.$fileExt",
            out.getName,
            Some(extractHdr),
            "templateModelId" → modelId
        )

        logln(s"Model file stub created: ${c(out.getCanonicalPath)}")
    }

    /**
      *
      * @param cmd Command descriptor.
      * @param args Arguments, if any, for this command.
      * @param repl Whether or not executing from REPL.
      */
    private def cmdGenProject(cmd: Command, args: Seq[Argument], repl: Boolean): Unit = {
        val outputDir = get(cmd, args, "outputDir", ".")
        val baseName = get(cmd, args,"baseName")
        val lang = get(cmd, args,"lang", "java").toLowerCase
        val buildTool = get(cmd, args,"buildTool", "mvn").toLowerCase
        val pkgName = get(cmd, args,"packageName", "org.apache.nlpcraft.demo").toLowerCase
        val fileType = get(cmd, args,"modelType", "yaml").toLowerCase
        val overrideFlag = get(cmd, args,"override", "false").toLowerCase
        val dst = new File(outputDir, baseName)
        val pkgDir = pkgName.replaceAll("\\.", "/")
        val clsName = s"${baseName.head.toUpper}${baseName.tail}"
        val variant = s"$lang-$buildTool"
        val inFolder = s"nlpcraft-$variant"
        val isJson = fileType == "json" || fileType == "js"

        checkSupported(cmd, "lang", lang, "java", "scala", "kotlin")
        checkSupported(cmd,"buildTool", buildTool, "mvn", "gradle", "sbt")
        checkSupported(cmd,"fileType", fileType, "yaml", "yml", "json", "js")
        checkSupported(cmd,"override", overrideFlag, "true", "false")

        def checkJavaName(v: String, name: String): Unit =
            if (!SourceVersion.isName(v))
                throw InvalidParameter(cmd, name)

        checkJavaName(clsName, "baseName")
        checkJavaName(pkgName, "packageName")

        // Prepares output folder.
        if (dst.isFile)
            throw new NCE(s"Invalid folder: ${c(dst.getAbsolutePath)}")
        else {
            if (!dst.exists()) {
                if (!dst.mkdirs())
                    throw new NCE(s"Couldn't create folder: ${c(dst.getAbsolutePath)}")
            }
            else {
                if (overrideFlag == "true")
                    U.clearFolder(dst.getAbsolutePath)
                else
                    throw new NCE(s"Folder already exists: ${c(dst.getAbsolutePath)}")
            }
        }

        @throws[NCE]
        def cp(in: String, extractHeader: Option[Seq[String] ⇒ (Int, Int)], repls: (String, String)*): Unit =
            copy(inFolder, dst, in, in, extractHeader, repls :_*)

        @throws[NCE]
        def cpAndRename(in: String, out: String, extractHdr: Option[Seq[String] ⇒ (Int, Int)], repls: (String, String)*): Unit =
            copy(inFolder, dst, in, out, extractHdr, repls :_*)

        @throws[NCE]
        def cpCommon(langDir: String, langExt: String): Unit = {
            cp(".gitignore", None)

            val (startClause, exampleClause) =
                langExt match {
                    case "java" ⇒ (s"NCEmbeddedProbe.start($clsName.class);", "Java example")
                    case "kt" ⇒ (s"NCEmbeddedProbe.start($clsName::class.java)", "Kotlin example")
                    case "scala" ⇒ (s"NCEmbeddedProbe.start(classOf[$clsName])", "Scala example")

                    case  _ ⇒ throw new AssertionError(s"Unexpected language extension: $langExt")
                }

            cp(
                "readme.txt",
                None,
                "com.company.nlp.TemplateModel" → s"$pkgName.$clsName",
                "NCEmbeddedProbe.start\\(TemplateModel.class\\);" → startClause,
                "Java example" → exampleClause,
                "templateModelId" → baseName
            )

            val resFileName =
                if (baseName.contains("_")) baseName else CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, baseName)

            cpAndRename(
                s"src/main/$langDir/com/company/nlp/TemplateModel.$langExt",
                s"src/main/$langDir/$pkgDir/$clsName.$langExt",
                // Suitable for all supported languages.
                Some(extractJavaHeader),
                "com.company.nlp" → s"$pkgName",
                "TemplateModel" → clsName,
                "template_model.yaml" → s"$resFileName.$fileType"
            )
            cpAndRename(
                s"src/main/resources/template_model.${if (isJson) "json" else "yaml"}",
                s"src/main/resources/$resFileName.$fileType",
                Some(if (isJson) extractJsonHeader else extractYamlHeader),
                "templateModelId" → baseName
            )
        }

        @throws[NCE]
        def cpPom(): Unit =
            cp(
                "pom.xml",
                Some(extractXmlHeader),
                "com.company.nlp" → pkgName,
                "myapplication" → baseName,
                "<nlpcraft.ver>(.*)</nlpcraft.ver>" → s"<nlpcraft.ver>${VER.version}</nlpcraft.ver>"
            )

        @throws[NCE]
        def cpGradle(): Unit = {
            cp("build.gradle",
                Some(extractGradleHeader),
                "com.company.nlp" → pkgName,
                "myapplication" → baseName,
                "'org.apache.nlpcraft:nlpcraft:(.*)'" → s"'org.apache.nlpcraft:nlpcraft:${VER.version}'"
            )
            cp(
                "settings.gradle",
                Some(extractGradleHeader),
                "myapplication" → baseName
            )
            cp("gradlew", None)
            cp("gradlew.bat", None)
        }

        @throws[NCE]
        def cpSbt(): Unit = {
            cp("build.sbt",
                Some(extractSbtHeader),
                "com.company.nlp" → pkgName,
                "myapplication" → baseName,
                (s"""libraryDependencies""" + " \\+= " + """"org.apache.nlpcraft" % "nlpcraft" % "(.*)"""") →
                (s"""libraryDependencies""" + " \\+= " +  s""""org.apache.nlpcraft" % "nlpcraft" % "${VER.version}"""")
            )
            cp("project/build.properties", Some(extractPropertiesHeader))
        }

        def folder2String(dir: File): String = {
            val sp = System.getProperty("line.separator")

            def get(f: File): List[StringBuilder] = {
                val name = if (f.isFile) s"${y(f.getName)}" else f.getName

                val buf = mutable.ArrayBuffer.empty[StringBuilder] :+ new StringBuilder().append(name)

                val children = {
                    val list = f.listFiles()

                    if (list == null) List.empty else list.sortBy(_.getName).toList
                }

                for {
                    child ← children
                    (v1, v2) = if (child != children.last) ("├── ", "│   ") else ("└── ", "    ")
                    sub = get(child)
                } {
                    buf += sub.head.insert(0, v1)
                    sub.tail.foreach(p ⇒ buf += p.insert(0, v2))
                }

                buf.toList
            }

            get(dir).map(line ⇒ s"$line$sp").mkString
        }

        try {
            variant match {
                case "java-mvn" ⇒ cpCommon("java", "java"); cpPom()
                case "java-gradle" ⇒ cpCommon("java", "java"); cpGradle()

                case "kotlin-mvn" ⇒ cpCommon("kotlin", "kt"); cpPom()
                case "kotlin-gradle" ⇒ cpCommon("kotlin", "kt"); cpGradle()

                case "scala-mvn" ⇒ cpCommon("scala", "scala"); cpPom()
                case "scala-gradle" ⇒ cpCommon("scala", "scala"); cpGradle()
                case "scala-sbt" ⇒ cpCommon("scala", "scala"); cpSbt()

                case _ ⇒ throw new NCE(s"Unsupported combination of '${c(lang)}' and '${c(buildTool)}'.")
            }

            logln(s"Project created: ${c(dst.getCanonicalPath)}")
            logln(folder2String(dst))
        }
        catch {
            case e: NCE ⇒
                try
                    U.clearFolder(dst.getAbsolutePath, delFolder = true)
                catch {
                    case _: NCE ⇒ // No-op.
                }

                throw e
        }
    }

    /**
     *
     * @param cmd
     * @param path
     * @param json
     */
    private def httpRest(cmd: Command, path: String, json: String): Unit = {
        if (!U.isValidJson(json))
            throw MalformedJson()

        if (!REST_SPEC.exists(_.path == path))
            throw InvalidParameter(cmd, "path")

        val spinner = new NCAnsiSpinner(
            term.writer(),
            // ANSI is NOT disabled & we ARE NOT running from IDEA or Eclipse...
            NCAnsi.isEnabled && IS_SCRIPT
        )

        spinner.start()

        // Make the REST call.
        val resp =
            try
                httpPostResponse(getRestEndpointFromBeacon, path, json)
            finally
                spinner.stop()

        // Ack HTTP response code.
        logln(s"HTTP ${if (resp.code == 200) g("200") else r(resp.code)}")

        if (U.isValidJson(resp.data))
            logln(U.colorJson(U.prettyJson(resp.data)))
        else {
            if (resp.code == 200)
                logln(s"HTTP response: ${resp.data}")
            else
                error(s"HTTP error: ${resp.data}")
        }

        if (resp.code == 200) {
            if (path == "signin")
                state.accessToken = Some(U.getJsonStringField(resp.data, "acsTok"))
            else if (path == "signout")
                state.accessToken = None
        }
    }

    /**
     *
     */
    private def repl(): Unit = {
        loadServerBeacon(autoSignIn = true) match {
            case Some(beacon) ⇒ logServerInfo(beacon)
            case None ⇒ ()
        }

        if (state.accessToken.isDefined)
            logln(s"REST server signed in with default '${c("admin@admin.com")}' user.")

        val parser = new DefaultParser()

        parser.setEofOnUnclosedBracket(Bracket.CURLY, Bracket.ROUND, Bracket.SQUARE)
        parser.setEofOnUnclosedQuote(true)
        parser.regexCommand("")
        parser.regexVariable("")

        val completer: Completer = new Completer {
            private val cmds = CMDS.map(c ⇒ (c.name, c.synopsis, c.group))

            /**
             *
             * @param disp
             * @param desc
             * @param completed
             * @return
             */
            private def mkCandidate(disp: String, grp: String, desc: String, completed: Boolean): Candidate =
                new Candidate(disp, disp, grp, desc, null, null, completed)

            override def complete(reader: LineReader, line: ParsedLine, candidates: util.List[Candidate]): Unit = {
                val words = line.words().asScala

                if (words.nonEmpty && words.head.nonEmpty && words.head.head == '$') { // Don't complete if the line starts with '$'.
                    // No-op.
                }
                else if (words.isEmpty || !cmds.map(_._1).contains(words.head))
                    candidates.addAll(cmds.map(n ⇒ {
                        val name = n._1
                        val desc = n._2.substring(0, n._2.length - 1) // Remove last '.'.
                        val grp = s"${n._3}:"

                        mkCandidate(
                            disp = name,
                            grp = grp,
                            desc = desc,
                            completed = true
                        )
                    }).asJava)
                else {
                    val cmd = words.head

                    val OPTIONAL_GRP = "Optional:"
                    val MANDATORY_GRP = "Mandatory:"
                    val DFTL_USER_GRP = "Default user:"
                    val CMDS_GRP = "Commands:"

                    candidates.addAll(CMDS.find(_.name == cmd) match {
                        case Some(c) ⇒
                            c.params.filter(!_.synthetic).flatMap(param ⇒ {
                                val hasVal = param.value.isDefined
                                val names = param.names.filter(_.startsWith("--")) // Skip shorthands from auto-completion.

                                names.map(name ⇒ mkCandidate(
                                    disp = if (hasVal) name + "=" else name,
                                    grp = if (param.optional) OPTIONAL_GRP else MANDATORY_GRP,
                                    desc = null,
                                    completed = !hasVal
                                ))
                            })
                                .asJava

                        case None ⇒ Seq.empty[Candidate].asJava
                    })

                    // For 'help' - add additional auto-completion/suggestion candidates.
                    if (cmd == HELP_CMD.name)
                        candidates.addAll(CMDS.map(c ⇒ s"--cmd=${c.name}").map(s ⇒
                            mkCandidate(
                                disp = s,
                                grp = CMDS_GRP,
                                desc = null,
                                completed = true
                            ))
                            .asJava
                        )

                    // For 'rest' or 'call' - add '--path' auto-completion/suggestion candidates.
                    if (cmd == REST_CMD.name || cmd == CALL_CMD.name) {
                        val pathParam = REST_CMD.findParameterById("path")
                        val hasPathAlready = words.exists(w ⇒ pathParam.names.exists(x ⇒ w.startsWith(x)))

                        if (!hasPathAlready)
                            candidates.addAll(
                                REST_SPEC.map(cmd ⇒ {
                                    val name = s"--path=${cmd.path}"

                                    mkCandidate(
                                        disp = name,
                                        grp = s"REST ${cmd.group}:",
                                        desc = cmd.desc,
                                        completed = true
                                    )
                                })
                                    .asJava
                            )
                    }

                    // For 'ask' and 'sugysn' - add additional model IDs auto-completion/suggestion candidates.
                    if (cmd == ASK_CMD.name || cmd == SUGSYN_CMD.name)
                        candidates.addAll(
                            state.probes.flatMap(_.models.toList).map(mdl ⇒ {
                                mkCandidate(
                                    disp = s"--mdlId=${mdl.id}",
                                    grp = MANDATORY_GRP,
                                    desc = null,
                                    completed = true
                                )
                            })
                                .asJava
                        )

                    // For 'call' - add additional auto-completion/suggestion candidates.
                    if (cmd == CALL_CMD.name) {
                        val pathParam = CALL_CMD.findParameterById("path")

                        words.find(w ⇒ pathParam.names.exists(x ⇒ w.startsWith(x))) match {
                            case Some(p) ⇒
                                val path = p.substring(p.indexOf('=') + 1)

                                REST_SPEC.find(_.path == path) match {
                                    case Some(spec) ⇒
                                        candidates.addAll(
                                            spec.params.map(param ⇒ {
                                                mkCandidate(
                                                    disp = s"--${param.name}",
                                                    grp = if (param.optional) OPTIONAL_GRP else MANDATORY_GRP,
                                                    desc = null,
                                                    completed = false
                                                )
                                            })
                                                .asJava
                                        )

                                        // Add 'acsTok' auto-suggestion.
                                        if (spec.params.exists(_.name == "acsTok") && state.accessToken.isDefined)
                                            candidates.add(
                                                mkCandidate(
                                                    disp = s"--acsTok=${state.accessToken.get}",
                                                    grp = MANDATORY_GRP,
                                                    desc = null,
                                                    completed = true
                                                )
                                            )

                                        // Add 'mdlId' auto-suggestion.
                                        if (spec.params.exists(_.name == "mdlId") && state.probes.nonEmpty)
                                            candidates.addAll(
                                                state.probes.flatMap(_.models.toList).map(mdl ⇒ {
                                                    mkCandidate(
                                                        disp = s"--mdlId=${mdl.id}",
                                                        grp = MANDATORY_GRP,
                                                        desc = null,
                                                        completed = true
                                                    )
                                                })
                                                    .asJava
                                            )

                                        // Add default 'email' and 'passwd' auto-suggestion for 'signin' path.
                                        if (path == "signin") {
                                            candidates.add(
                                                mkCandidate(
                                                    disp = "--email=admin@admin.com",
                                                    grp = DFTL_USER_GRP,
                                                    desc = null,
                                                    completed = true
                                                )
                                            )
                                            candidates.add(
                                                mkCandidate(
                                                    disp = "--passwd=admin",
                                                    grp = DFTL_USER_GRP,
                                                    desc = null,
                                                    completed = true
                                                )
                                            )
                                        }

                                    case None ⇒ ()
                                }

                            case None ⇒ ()
                        }
                    }
                }
            }
        }

        class ReplHighlighter extends Highlighter {
            override def highlight(reader: LineReader, buffer: String): AttributedString =
                AttributedString.fromAnsi(
                    CMD_NAME.matcher(
                        CMD_PARAM.matcher(
                            buffer
                        )
                        .replaceAll("$1" + c("$2"))
                    )
                    .replaceAll(bo(g("$1")) + "$2")
                )

            override def setErrorPattern(errorPattern: Pattern): Unit = ()
            override def setErrorIndex(errorIndex: Int): Unit = ()
        }

        val reader = LineReaderBuilder
            .builder
            .appName("NLPCraft")
            .terminal(term)
            .completer(completer)
            .parser(parser)
            .highlighter(new ReplHighlighter())
            .history(new DefaultHistory())
            .variable(LineReader.SECONDARY_PROMPT_PATTERN, s"${g("...>")} ")
            .variable(LineReader.INDENTATION, 2)
            .build

        reader.setOpt(LineReader.Option.AUTO_FRESH_LINE)
        reader.unsetOpt(LineReader.Option.INSERT_TAB)
        reader.unsetOpt(LineReader.Option.BRACKETED_PASTE)
        reader.setOpt(LineReader.Option.DISABLE_EVENT_EXPANSION)
        reader.setVariable(
            LineReader.HISTORY_FILE,
            new File(SystemUtils.getUserHome, HIST_PATH).getAbsolutePath
        )

        logln(s"Hit ${rv(" Tab ")} or type '${c("help")}' to get help and ${rv(" ↑ ")} or ${rv(" ↓ ")} to scroll through history.")
        logln(s"Type '${c("quit")}' to exit.")

        var exit = false

        val pinger = U.mkThread("repl-server-pinger") { t ⇒
            while (!t.isInterrupted) {
                loadServerBeacon()

                Thread.sleep(10.secs)
            }
        }

        pinger.start()

        while (!exit) {
            val rawLine = try {
                val srvStr = bo(s"${if (state.isServerOnline) s"ON " else s"OFF "}")
                val acsTokStr = bo(s"${state.accessToken.getOrElse("<signed out>")} ")

                val prompt1 = rb(w(s" server: $srvStr")) // Server status.
                val prompt2 = wb(k(s" acsTok: $acsTokStr")) // Access toke, if any.
                val prompt3 = kb(g(s" ${Paths.get("").toAbsolutePath} ")) // Current working directory.

                reader.printAbove("\n" + prompt1 + ":" + prompt2 + ":" + prompt3)
                reader.readLine(s"${g(">")} ")
            }
            catch {
                case _: UserInterruptException ⇒ "" // Ignore.
                case _: EndOfFileException ⇒ null
                case _: Exception ⇒ "" // Guard against JLine hiccups.
            }

            if (rawLine == null || QUIT_CMD.name == rawLine.trim)
                exit = true
            else {
                val line = rawLine
                    .trim()
                    .replace("\n", "")
                    .replace("\t", " ")
                    .trim()

                if (line.nonEmpty)
                    try
                        doCommand(splitBySpace(line), repl = true)
                    catch {
                        case e: SplitError ⇒
                            val idx = e.index
                            val lineX = line.substring(0, idx) + r(line.substring(idx, idx + 1) ) + line.substring(idx + 1)
                            val dashX = c("-" * idx) + r("^") + c("-" * (line.length - idx - 1))

                            error(s"Uneven quotes or brackets:")
                            error(s"  ${r("+-")} $lineX")
                            error(s"  ${r("+-")} $dashX")
                    }
            }
        }

        U.stopThread(pinger)

        // Save command history.
        ignoring(classOf[IOException]) {
            reader.getHistory.save()
        }
    }

    /**
     *
     * @param cmd Command descriptor.
     * @param args Arguments, if any, for this command.
     * @param repl Whether or not executing from REPL.
     */
    private def cmdVersion(cmd: Command, args: Seq[Argument], repl: Boolean): Unit =
        if (args.isEmpty)
            logln((
                new NCAsciiTable
                    += ("Version:", c(VER.version))
                    += ("Release date:", c(VER.date.toString))
                ).toString
            )
        else {
            val isS = args.exists(_.parameter.id == "semver")
            val isD = args.exists(_.parameter.id == "reldate")

            if (isS || isD) {
                if (isS)
                    logln(s"${VER.version}")
                if (isD)
                    logln(s"${VER.date}")
            }
            else
                error(s"Invalid parameters: ${args.mkString(", ")}")
        }


    /**
     *
     * @param msg
     */
    private def error(msg: String = ""): Unit = {
        // Make sure we exit with non-zero status.
        exitStatus = 1

        if (msg != null && msg.nonEmpty) {
            term.writer().println(s"${y("ERR:")} ${if (msg.head.isLower) msg.head.toUpper + msg.tail else msg}")
            term.flush()
        }
    }

    /**
     *
     * @param msg
     */
    private def logln(msg: String = ""): Unit = {
        term.writer().println(msg)
        term.flush()
    }

    /**
     *
     * @param msg
     */
    private def log(msg: String = ""): Unit = {
        term.writer().print(msg)
        term.flush()
    }

    /**
     *
     */
    private def errorUnknownCommand(cmd: String): Unit = {
        val c2 = c(s"'$cmd'")
        val h2 = c(s"'help'")

        error(s"Unknown command $c2, type $h2 to get help.")
    }

    /**
     * Prints out the version and copyright title header.
     */
    private def title(): Unit = {
        logln(U.asciiLogo())
        logln(s"$NAME ver. ${VER.version}")
        logln()
    }

    /**
     *
     * @param baseUrl
     * @param cmd
     * @return
     */
    private def prepRestUrl(baseUrl: String, cmd: String): String =
        if (baseUrl.endsWith("/")) s"${baseUrl}api/v1/$cmd" else s"$baseUrl/api/v1/$cmd"

    /**
     * Posts HTTP POST request.
     *
     * @param baseUrl Base endpoint URL.
     * @param cmd REST call command.
     * @param resp
     * @param json JSON string.
     * @return
     * @throws IOException
     */
    private def httpPost[T](baseUrl: String, cmd: String, resp: ResponseHandler[T], json: String): T = {
        val post = new HttpPost(prepRestUrl(baseUrl, cmd))

        post.setHeader("Content-Type", "application/json")
        post.setEntity(new StringEntity(json, "UTF-8"))

        try
            HttpClients.createDefault().execute(post, resp)
        finally
            post.releaseConnection()
    }

    /**
     *
     * @param endpoint
     * @param path
     * @param json
     * @return
     */
    private def httpPostResponse(endpoint: String, path: String, json: String): HttpRestResponse =
        httpPost(endpoint, path, mkHttpHandler(resp ⇒ {
            val status = resp.getStatusLine

            HttpRestResponse(
                status.getStatusCode,
                Option(EntityUtils.toString(resp.getEntity)).getOrElse(
                    throw new IllegalStateException(s"Unexpected REST error: ${status.getReasonPhrase}")
                )
            )
        }), json)

    /**
     *
     * @param endpoint
     * @param path
     * @param json
     * @return
     */
    private def httpPostResponseJson(endpoint: String, path: String, json: String): Option[String] =
        httpPost(endpoint, path, mkHttpHandler(resp ⇒ {
            val status = resp.getStatusLine

            if (status.getStatusCode == 200)
                Option(EntityUtils.toString(resp.getEntity))
            else
                None
        }), json)

    /**
     * Posts HTTP GET request.
     *
     * @param endpoint Base endpoint URL.
     * @param path REST call command.
     * @param resp
     * @param jsParams
     * @return
     * @throws IOException
     */
    private def httpGet[T](endpoint: String, path: String, resp: ResponseHandler[T], jsParams: (String, AnyRef)*): T = {
        val bldr = new URIBuilder(prepRestUrl(endpoint, path))

        jsParams.foreach(p ⇒ bldr.setParameter(p._1, p._2.toString))

        val get = new HttpGet(bldr.build())

        try
            HttpClients.createDefault().execute(get, resp)
        finally
            get.releaseConnection()
    }

    /**
     * Splits given string by spaces taking into an account double and single quotes,
     * '\' escaping as well as checking for uneven <>, {}, [], () pairs.
     *
     * @param line
     * @return
     */
    @throws[SplitError]
    private def splitBySpace(line: String): Seq[String] = {
        val lines = mutable.Buffer.empty[String]
        val buf = new StringBuilder
        var stack = List.empty[Char]
        var escape = false
        var index = 0

        def stackHead: Char = stack.headOption.getOrElse(Char.MinValue)

        for (ch ← line) {
            if (ch.isWhitespace && !stack.contains('"') && !stack.contains('\'') && !escape) {
                if (buf.nonEmpty) {
                    lines += buf.toString()
                    buf.clear()
                }
            }
            else if (ch == '\\') {
                if (escape)
                    buf += ch
                else
                    // SKip '\'.
                    escape = true
            }
            else if (ch == '"' || ch == '\'') {
                if (!escape) {
                    if (!stack.contains(ch))
                        stack ::= ch // Push.
                    else if (stackHead == ch)
                        stack = stack.tail // Pop.
                    else
                        throw SplitError(index)
                }

                buf += ch
            }
            else if (OPEN_BRK.contains(ch)) {
                stack ::= ch // Push.

                buf += ch
            }
            else if (CLOSE_BRK.contains(ch)) {
                if (stackHead != BRK_PAIR(ch))
                    throw SplitError(index)

                stack = stack.tail // Pop.

                buf += ch
            }
            else {
                if (escape)
                    buf += '\\' // Put back '\'.

                buf += ch
            }

            // Drop escape flag.
            if (escape && ch != '\\')
                escape = false

            index += 1
        }

        if (stack.nonEmpty)
            throw SplitError(index - 1)

        if (buf.nonEmpty)
            lines += buf.toString()

        lines.map(_.trim)
    }

    /**
     *
     * @param cmd
     * @param args
     * @return
     */
    private def processParameters(cmd: Command, args: Seq[String]): Seq[Argument] =
        args.map { arg ⇒
            val parts = arg.split("=")

            def mkError() = new IllegalArgumentException(s"Invalid parameter: ${c(arg)}")

            if (parts.size > 2)
                throw mkError()

            val name = if (parts.size == 1) arg.trim else parts(0).trim
            val value = if (parts.size == 1) None else Some(parts(1).trim)
            val hasSynth = cmd.params.exists(_.synthetic)

            if (name.endsWith("=")) // Missing value or extra '='.
                throw mkError()

            cmd.findParameterByNameOpt(name) match {
                case None ⇒
                    if (hasSynth)
                        Argument(Parameter(
                            id = name.substring(2), // Remove single '--' from the beginning.
                            names = Seq(name),
                            value = value,
                            synthetic = true,
                            desc = null
                        ), value) // Synthetic argument.
                    else
                        throw mkError()

                case Some(param) ⇒
                    if ((param.value.isDefined && value.isEmpty) || (param.value.isEmpty && value.isDefined))
                        throw mkError()

                    Argument(param, value)
            }
        }

    /**
     *
     * @param args
     * @param repl
     */
    private def processAnsi(args: Seq[String], repl: Boolean): Unit = {
        args.find(_ == NO_ANSI_CMD.name) match {
            case Some(_) ⇒ NO_ANSI_CMD.body(NO_ANSI_CMD, Seq.empty, repl)
            case None ⇒ ()
        }
        args.find(_ == ANSI_CMD.name) match {
            case Some(_) ⇒ ANSI_CMD.body(ANSI_CMD, Seq.empty, repl)
            case None ⇒ ()
        }
    }

    /**
     *
     * @param args
     */
    private def execOsCmd(args: Seq[String]): Unit = {
        val pb = new ProcessBuilder(args.asJava).inheritIO()

        val proc = pb.start()

        try
            proc.waitFor()
        catch {
            case _: InterruptedException ⇒ () // Exit.
        }
    }

    /**
     * Processes a single command defined by the given arguments.
     *
     * @param args
     * @param repl Whether or not called from 'repl' mode.
     */
    @throws[Exception]
    private def doCommand(args: Seq[String], repl: Boolean): Unit = {
        if (args.nonEmpty) {
            if (args.head.head == '$') {
                val head = args.head.tail.trim // Remove '$' from 1st argument.
                val tail = args.tail.toList

                try
                    execOsCmd(if (head.isEmpty) tail else head :: tail)
                catch {
                    case e: Exception ⇒ error(e.getLocalizedMessage)
                }
            }
            else {
                // Process 'no-ansi' and 'ansi' commands first.
                processAnsi(args, repl)

                // Remove 'no-ansi' and 'ansi' commands from the argument list, if any.
                val xargs = args.filter(arg ⇒ arg != NO_ANSI_CMD.name && arg != ANSI_CMD.name)

                if (xargs.nonEmpty) {
                    val cmd = xargs.head

                    CMDS.find(_.name == cmd) match {
                        case Some(cmd) ⇒
                            // Reset error code.
                            exitStatus = 0

                            try
                                cmd.body(cmd, processParameters(cmd, xargs.tail), repl)
                            catch {
                                case e: Exception ⇒ error(e.getLocalizedMessage)
                            }

                        case None ⇒ errorUnknownCommand(cmd)
                    }
                }
            }
        }
    }

    /**
     *
     * @param args
     */
    private def boot(args: Array[String]): Unit = {
        new Thread() {
            override def run(): Unit = {
                U.gaScreenView("cli")
            }
        }
        .start()

        // Initialize OS-aware terminal.
        term = TerminalBuilder.builder()
            .name(NAME)
            .system(true)
            .nativeSignals(true)
            .signalHandler(Terminal.SignalHandler.SIG_IGN)
            .dumb(true)
            .jansi(true)
            .build()

        // Process 'no-ansi' and 'ansi' commands first (before ASCII title is shown).
        processAnsi(args, repl = false)

        title()

        if (args.isEmpty)
            repl()
        else
            doCommand(args.toSeq, repl = false)

        sys.exit(exitStatus)
    }

    // Boot up.
    boot(args)
}
