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

package org.apache.nlpcraft

import java.util.concurrent.Callable
import java.util.function.{BiPredicate, Consumer, Supplier, Function ⇒ JFunction, Predicate ⇒ JPredicate}
import org.apache.nlpcraft.common.ansi.NCAnsi._
import org.apache.nlpcraft.common.util._

import scala.language.implicitConversions

/**
  * Package scope.
  */
package object common {
    // Type aliases for `org.apache.nlpcraft`
    type NCE = NCException
    final val U = NCUtils
    
    // Internal deep debug flag (more verbose tracing).
    final val DEEP_DEBUG = false
    
    // Model and token **internal** metadata keys.
    final val MDL_META_ALL_ELM_IDS_KEY = "__NLPCRAFT_MDL_META_ALL_ELM_IDS"
    final val MDL_META_ALL_GRP_IDS_KEY = "__NLPCRAFT_MDL_META_ALL_GRP_IDS"
    final val MDL_META_ALL_ALIASES_KEY = "__NLPCRAFT_MDL_META_ALL_ALIASES"
    final val TOK_META_ALIASES_KEY = "__NLPCRAFT_TOK_META_ALIASES"

    def G: String = ansiGreenFg
    def R: String = ansiRedFg
    def C: String = ansiCyanFg
    def Y: String = ansiYellowFg
    def W: String = ansiWhiteFg
    def B: String = ansiBlueFg
    def BO: String = ansiBold
    def K: String = ansiBlackFg
    def GB: String = ansiGreenBg
    def RB: String = ansiRedBg
    def CB: String = ansiCyanBg
    def YB: String = ansiYellowBg
    def WB: String = ansiWhiteBg
    def BB: String = ansiBlueBg
    def KB: String = ansiBlackBg
    def RST: String = ansiReset
    def g(s: Any): String = s"$G${s.toString}$RST"
    def r(s: Any): String = s"$R${s.toString}$RST"
    def c(s: Any): String = s"$C${s.toString}$RST"
    def y(s: Any): String = s"$Y${s.toString}$RST"
    def w(s: Any): String = s"$W${s.toString}$RST"
    def b(s: Any): String = s"$B${s.toString}$RST"
    def k(s: Any): String = s"$K${s.toString}$RST"
    def gb(s: Any): String = s"$GB${s.toString}$RST"
    def rb(s: Any): String = s"$RB${s.toString}$RST"
    def cb(s: Any): String = s"$CB${s.toString}$RST"
    def yb(s: Any): String = s"$YB${s.toString}$RST"
    def wb(s: Any): String = s"$WB${s.toString}$RST"
    def bb(s: Any): String = s"$BB${s.toString}$RST"
    def kb(s: Any): String = s"$KB${s.toString}$RST"

    // Real color effect shortcuts...
    def rv(s: Any): String = s"$ansiReversed${s.toString}$RST"
    def bo(s: Any): String = s"$ansiBold${s.toString}$RST"

    /**
     * Pimps integers with KB, MB, GB units of measure.
     *
     * @param v Integer value.
     */
    implicit class IntMemoryUnits(v: Int) {
        def TB: Int = v * 1024 * 1024 * 1024 * 1024
        def GB: Int = v * 1024 * 1024 * 1024
        def MB: Int = v * 1024 * 1024
        def KB: Int = v * 1024
        def tb: Int = TB
        def gb: Int = GB
        def mb: Int = MB
        def kb: Int = KB
    }

    /**
     * Pimps longs with KB, MB, GB units of measure.
     *
     * @param v Long value.
     */
    implicit class LongMemoryUnits(v: Long) {
        def TB: Long = v * 1024 * 1024 * 1024 * 1024
        def GB: Long = v * 1024 * 1024 * 1024
        def MB: Long = v * 1024 * 1024
        def KB: Long = v * 1024
        def tb: Long = TB
        def gb: Long = GB
        def mb: Long = MB
        def kb: Long = KB
    }


    /**
     * Pimps integers with time units.
     *
     * @param v Integer value.
     */
    implicit class IntTimeUnits(v: Int) {
        def MSECS: Int = v
        def MS: Int = v
        def SECS: Int = v * 1000
        def MINS: Int = v * 1000 * 60
        def HOURS: Int = v * 1000 * 60 * 60
        def DAYS: Int = v * 1000 * 60 * 60 * 24
        def FPS: Int = 1000 / v
        def ms: Int = MS
        def secs: Int = SECS
        def mins: Int = MINS
        def hours: Int = HOURS
        def days: Int = DAYS
        def fps: Int = 1000 / v
    }

    /**
     * Pimps long with time units.
     *
     * @param v Long value.
     */
    implicit class LongTimeUnits(v: Long) {
        def MSECS: Long = v
        def MS: Long = v
        def SECS: Long = v * 1000
        def MINS: Long = v * 1000 * 60
        def HOURS: Long = v * 1000 * 60 * 60
        def DAYS: Long = v * 1000 * 60 * 60 * 24
        def FPS: Long = 1000 / v
        def ms: Long = MS
        def secs: Long = SECS
        def mins: Long = MINS
        def hours: Long = HOURS
        def days: Long = DAYS
        def fps: Long = 1000 / v
    }

    /**
     * 
     * @param f
     * @tparam T
     * @return
     */
    implicit def toJavaConsumer[T](f: T ⇒ Unit): Consumer[T] = (t: T) => f(t)
    
    /**
      *
      * @param f
      * @tparam A
      * @return
      */
    implicit def toJavaSupplier[A](f: () ⇒ A): Supplier[A] = () => f()
    
    /**
      *
      * @param f
      * @tparam A
      * @tparam B
      * @return
      */
    implicit def toJavaFunction[A, B](f: A ⇒ B): JFunction[A, B] = (a: A) => f(a)
    
    /**
      *
      * @param f
      * @tparam A
      * @return
      */
    implicit def toJavaPredicate[A](f: A ⇒ Boolean): JPredicate[A] = (a: A) => f(a)
    
    /**
      *
      * @param predicate
      * @tparam A
      * @tparam B
      * @return
      */
    implicit def toJavaBiPredicate[A, B](predicate: (A, B) ⇒ Boolean): BiPredicate[A, B] = (a: A, b: B) => predicate(a, b)
    
    /**
      * @param f Lambda to convert.
      * @return Runnable object.
      */
    implicit def toRunnable(f: () ⇒ Unit): Runnable =
        new Runnable() {
            override def run(): Unit = f()
        }
    
    /**
      * @param f Lambda to convert.
      * @return Callable object.
      */
    implicit def toCallable[R](f: () ⇒ R): Callable[R] =
        () => f()
}
