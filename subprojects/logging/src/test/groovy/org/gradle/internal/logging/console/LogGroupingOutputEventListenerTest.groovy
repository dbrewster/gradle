/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.logging.console

import org.gradle.api.logging.LogLevel
import org.gradle.internal.SystemProperties
import org.gradle.internal.logging.OutputSpecification
import org.gradle.internal.logging.events.BatchOutputEventListener
import org.gradle.internal.logging.events.EndOutputEvent
import org.gradle.internal.logging.events.LogLevelChangeEvent
import org.gradle.internal.logging.events.OperationIdentifier
import org.gradle.internal.logging.events.OutputEvent
import org.gradle.internal.logging.events.ProgressCompleteEvent
import org.gradle.internal.logging.events.ProgressStartEvent
import org.gradle.internal.progress.BuildOperationType
import org.gradle.util.MockExecutor
import spock.lang.Subject

class LogGroupingOutputEventListenerTest extends OutputSpecification {
    public static final OperationIdentifier ROOT_BUILD_OPERATION_ID = new OperationIdentifier(0L)
    public static final String EOL = SystemProperties.getInstance().getLineSeparator()
    def downstreamListener = Mock(BatchOutputEventListener)
    def executor = new MockExecutor()

    @Subject listener = new LogGroupingOutputEventListener(downstreamListener, executor)

    def "forwards uncategorized events"() {
        def logLevelChangeEvent = new LogLevelChangeEvent(LogLevel.LIFECYCLE)

        when:
        listener.onOutput(logLevelChangeEvent)

        then:
        1 * downstreamListener.onOutput(logLevelChangeEvent)
        0 * _
    }

    def "forwards logs with no group"() {
        given:
        def event = event('message')

        when:
        listener.onOutput(event)

        then:
        1 * downstreamListener.onOutput(event)
        0 * _
    }

    def "forwards a group of logs for a task"() {
        given:
        def taskStartEvent = new ProgressStartEvent(new OperationIdentifier(-3L), new OperationIdentifier(-4L), tenAm, CATEGORY, "Execute :foo", ":foo", null, null, new OperationIdentifier(2L), null, BuildOperationType.TASK)
        def warningMessage = event('Warning: some deprecation or something', LogLevel.WARN, taskStartEvent.buildOperationId)

        when:
        listener.onOutput([taskStartEvent, warningMessage])

        then:
        0 * _

        when:
        listener.onOutput(new ProgressCompleteEvent(taskStartEvent.progressOperationId, tenAm, CATEGORY, 'Complete: :foo', null))

        then:
        1 * downstreamListener.onOutput({
            it.size() == 5
            it.first().getMessage() == '[Execute :foo]'
            it.get(2).equals(warningMessage)
            it.last().getMessage() == ""
        })
        0 * _
    }

    def "groups logs for child operations of tasks"() {
        given:
        def taskStartEvent = new ProgressStartEvent(new OperationIdentifier(-3L), new OperationIdentifier(-4L), tenAm, CATEGORY, "Execute :foo", ":foo", null, null, new OperationIdentifier(2L), null, BuildOperationType.TASK)
        def subtaskStartEvent = new ProgressStartEvent(new OperationIdentifier(-4L), new OperationIdentifier(-5L), tenAm, CATEGORY, ":foo subtask", "subtask", null, null, new OperationIdentifier(3L), taskStartEvent.buildOperationId, BuildOperationType.UNCATEGORIZED)
        def warningMessage = event('Child task log message', LogLevel.WARN, subtaskStartEvent.buildOperationId)
        def subTaskCompleteEvent = new ProgressCompleteEvent(subtaskStartEvent.progressOperationId, tenAm, CATEGORY, 'Complete: subtask', 'subtask complete')
        def taskCompleteEvent = new ProgressCompleteEvent(taskStartEvent.progressOperationId, tenAm, CATEGORY, 'Complete: :foo', 'UP-TO-DATE')

        when:
        listener.onOutput([taskStartEvent, subtaskStartEvent, warningMessage, subTaskCompleteEvent])

        then:
        0 * _

        when:
        listener.onOutput(taskCompleteEvent)

        then:
        1 * downstreamListener.onOutput({
            it.size() == 7
            it.get(0).getMessage() == '[Execute :foo]'
            it.get(1) instanceof ProgressStartEvent
            it.get(2) instanceof ProgressStartEvent
            it.get(3).equals(warningMessage)
            it.get(4) instanceof ProgressCompleteEvent
            it.get(5) instanceof ProgressCompleteEvent
            it.get(6).getMessage() == ""
        })
        0 * _
    }

    def "flushes all remaining groups on end of build"() {
        given:
        def taskStartEvent = new ProgressStartEvent(new OperationIdentifier(-3L), new OperationIdentifier(-4L), tenAm, CATEGORY, "Execute :foo", ":foo", null, null, new OperationIdentifier(2L), null, BuildOperationType.TASK)
        def warningMessage = event('Warning: some deprecation or something', LogLevel.WARN, taskStartEvent.buildOperationId)
        def endBuildEvent = new EndOutputEvent()

        when:
        listener.onOutput([taskStartEvent, warningMessage, endBuildEvent])

        then:
        1 * downstreamListener.onOutput(_ as ArrayList<OutputEvent>)
        1 * downstreamListener.onOutput(endBuildEvent)
        0 * _
    }

    def "does not forward group with no logs"() {
        given:
        def taskStartEvent = new ProgressStartEvent(new OperationIdentifier(-3L), new OperationIdentifier(-4L), tenAm, CATEGORY, "Execute :foo", ":foo", null, null, new OperationIdentifier(2L), null, BuildOperationType.TASK)
        def completeEvent = new ProgressCompleteEvent(taskStartEvent.progressOperationId, tenAm, CATEGORY, 'Complete: :foo', null)

        when:
        listener.onOutput([taskStartEvent, completeEvent])

        then:
        0 * _
    }

    void flush() {
        executor.runNow()
    }
}

//0 = {ProgressStartEvent@2524} "ProgressStart INITIALIZATION PHASE"
//1 = {ProgressStartEvent@2372} "ProgressStart Run init scripts"
//2 = {ProgressStartEvent@2409} "ProgressStart Apply script enterprise.gradle to build"
//3 = {ProgressStartEvent@2423} "ProgressStart Resolve dependencies of classpath"
//4 = {ProgressStartEvent@2525} "ProgressStart Resolve dependencies 'classpath'"
//5 = {ProgressCompleteEvent@2526} "ProgressComplete "
//6 = {ProgressCompleteEvent@2527} "ProgressComplete "
//7 = {ProgressStartEvent@2434} "ProgressStart Resolve artifacts of classpath"
//8 = {ProgressStartEvent@2445} "ProgressStart Resolve artifact build-scan-plugin.jar (com.gradle:build-scan-plugin:1.7.1)"
//9 = {ProgressCompleteEvent@2528} "ProgressComplete "
//10 = {ProgressCompleteEvent@2529} "ProgressComplete "
//11 = {ProgressCompleteEvent@2530} "ProgressComplete "
//12 = {ProgressCompleteEvent@2531} "ProgressComplete "
//13 = {ProgressStartEvent@2532} "ProgressStart Configure settings"
//14 = {ProgressCompleteEvent@2533} "ProgressComplete "
//15 = {ProgressCompleteEvent@2534} "ProgressComplete "
//16 = {ProgressStartEvent@2535} "ProgressStart CONFIGURATION PHASE"
//17 = {ProgressStartEvent@2536} "ProgressStart Executing 'rootProject {}' action"
//18 = {ProgressStartEvent@2537} "ProgressStart Cross-configure project :"
//19 = {ProgressStartEvent@2538} "ProgressStart Apply plugin com.gradle.scan.plugin.BuildScanPlugin to root project 'gradle-js-plugin'"
//20 = {StyledTextOutputEvent@2539} "[LIFECYCLE] [com.gradle.scan.plugin.BuildScanPlugin] <Normal>\n</Normal>"
//21 = {StyledTextOutputEvent@2540} "[LIFECYCLE] [com.gradle.scan.plugin.BuildScanPlugin] <Normal>WARNING: Using '-Dscan' for creating build scans is deprecated. Use Gradle command line option '--scan' instead.\n</Normal>"
//22 = {ProgressCompleteEvent@2541} "ProgressComplete "
//23 = {ProgressCompleteEvent@2542} "ProgressComplete "
//24 = {ProgressCompleteEvent@2543} "ProgressComplete "
//25 = {ProgressStartEvent@2544} "ProgressStart Configure build"
//26 = {LogEvent@2545} "[WARN] [org.gradle.util.DeprecationLogger] Parallel execution is an incubating feature."
//27 = {ProgressStartEvent@2546} "ProgressStart Configure project :"
//28 = {ProgressStartEvent@2547} "ProgressStart Configuring root project"
//29 = {ProgressStartEvent@2548} "ProgressStart Apply plugin org.gradle.help-tasks to root project 'gradle-js-plugin'"
//30 = {ProgressCompleteEvent@2549} "ProgressComplete "
//31 = {ProgressStartEvent@2550} "ProgressStart Apply script build.gradle to root project 'gradle-js-plugin'"
//32 = {ProgressStartEvent@2551} "ProgressStart Resolve dependencies of :classpath"
//33 = {ProgressStartEvent@2552} "ProgressStart Resolve dependencies ':classpath'"
//34 = {ProgressCompleteEvent@2553} "ProgressComplete "
//35 = {ProgressCompleteEvent@2554} "ProgressComplete "
//36 = {ProgressStartEvent@2555} "ProgressStart Resolve artifacts of :classpath"
//37 = {ProgressStartEvent@2556} "ProgressStart Resolve artifact plugin-publish-plugin.jar (com.gradle.publish:plugin-publish-plugin:0.9.4)"
//38 = {ProgressStartEvent@2557} "ProgressStart Resolve artifact gradle-bintray-plugin.jar (com.jfrog.bintray.gradle:gradle-bintray-plugin:1.6)"
//39 = {ProgressCompleteEvent@2558} "ProgressComplete "
//40 = {ProgressStartEvent@2559} "ProgressStart Resolve artifact maven-model.jar (org.apache.maven:maven-model:3.0.4)"
//41 = {ProgressStartEvent@2560} "ProgressStart Resolve artifact http-builder.jar (org.codehaus.groovy.modules.http-builder:http-builder:0.7.2)"
//42 = {ProgressCompleteEvent@2561} "ProgressComplete "
//43 = {ProgressStartEvent@2562} "ProgressStart Resolve artifact maven-ant-tasks.jar (org.apache.maven:maven-ant-tasks:2.1.3)"
//44 = {ProgressCompleteEvent@2563} "ProgressComplete "
//45 = {ProgressStartEvent@2564} "ProgressStart Resolve artifact httpclient.jar (org.apache.httpcomponents:httpclient:4.2.1)"
//46 = {ProgressCompleteEvent@2565} "ProgressComplete "
//47 = {ProgressStartEvent@2566} "ProgressStart Resolve artifact json-lib-jdk15.jar (net.sf.json-lib:json-lib:2.3)"
//48 = {ProgressCompleteEvent@2567} "ProgressComplete "
//49 = {ProgressStartEvent@2568} "ProgressStart Resolve artifact nekohtml.jar (net.sourceforge.nekohtml:nekohtml:1.9.16)"
//50 = {ProgressCompleteEvent@2569} "ProgressComplete "
//51 = {ProgressStartEvent@2570} "ProgressStart Resolve artifact xml-resolver.jar (xml-resolver:xml-resolver:1.2)"
//52 = {ProgressCompleteEvent@2571} "ProgressComplete "
//53 = {ProgressCompleteEvent@2572} "ProgressComplete "
//54 = {ProgressCompleteEvent@2573} "ProgressComplete "
//55 = {ProgressStartEvent@2574} "ProgressStart Resolve artifact ant.jar (org.apache.ant:ant:1.8.0)"
//56 = {ProgressStartEvent@2575} "ProgressStart Resolve artifact classworlds.jar (classworlds:classworlds:1.1-alpha-2)"
//57 = {ProgressStartEvent@2576} "ProgressStart Resolve artifact plexus-container-default.jar (org.codehaus.plexus:plexus-container-default:1.0-alpha-9-stable-1)"
//58 = {ProgressCompleteEvent@2577} "ProgressComplete "
//59 = {ProgressStartEvent@2578} "ProgressStart Resolve artifact plexus-interpolation.jar (org.codehaus.plexus:plexus-interpolation:1.11)"
//60 = {ProgressCompleteEvent@2579} "ProgressComplete "
//61 = {ProgressCompleteEvent@2580} "ProgressComplete "
//62 = {ProgressCompleteEvent@2581} "ProgressComplete "
//63 = {ProgressStartEvent@2582} "ProgressStart Resolve artifact maven-artifact.jar (org.apache.maven:maven-artifact:2.2.1)"
//64 = {ProgressStartEvent@2583} "ProgressStart Resolve artifact maven-project.jar (org.apache.maven:maven-project:2.2.1)"
//65 = {ProgressStartEvent@2584} "ProgressStart Resolve artifact maven-artifact-manager.jar (org.apache.maven:maven-artifact-manager:2.2.1)"
//66 = {ProgressCompleteEvent@2585} "ProgressComplete "
//67 = {ProgressStartEvent@2586} "ProgressStart Resolve artifact maven-error-diagnostics.jar (org.apache.maven:maven-error-diagnostics:2.2.1)"
//68 = {ProgressCompleteEvent@2587} "ProgressComplete "
//69 = {ProgressStartEvent@2588} "ProgressStart Resolve artifact maven-settings.jar (org.apache.maven:maven-settings:2.2.1)"
//70 = {ProgressCompleteEvent@2589} "ProgressComplete "
//71 = {ProgressStartEvent@2590} "ProgressStart Resolve artifact wagon-file.jar (org.apache.maven.wagon:wagon-file:1.0-beta-6)"
//72 = {ProgressCompleteEvent@2591} "ProgressComplete "
//73 = {ProgressStartEvent@2592} "ProgressStart Resolve artifact wagon-http-lightweight.jar (org.apache.maven.wagon:wagon-http-lightweight:1.0-beta-6)"
//74 = {ProgressCompleteEvent@2593} "ProgressComplete "
//75 = {ProgressStartEvent@2594} "ProgressStart Resolve artifact wagon-provider-api.jar (org.apache.maven.wagon:wagon-provider-api:1.0-beta-6)"
//76 = {ProgressCompleteEvent@2595} "ProgressComplete "
//77 = {ProgressStartEvent@2596} "ProgressStart Resolve artifact httpcore.jar (org.apache.httpcomponents:httpcore:4.2.1)"
//78 = {ProgressCompleteEvent@2597} "ProgressComplete "
//79 = {ProgressStartEvent@2598} "ProgressStart Resolve artifact commons-logging.jar (commons-logging:commons-logging:1.1.1)"
//80 = {ProgressCompleteEvent@2599} "ProgressComplete "
//81 = {ProgressStartEvent@2600} "ProgressStart Resolve artifact commons-codec.jar (commons-codec:commons-codec:1.6)"
//82 = {ProgressCompleteEvent@2601} "ProgressComplete "
//83 = {ProgressStartEvent@2602} "ProgressStart Resolve artifact commons-beanutils.jar (commons-beanutils:commons-beanutils:1.8.0)"
//84 = {ProgressCompleteEvent@2603} "ProgressComplete "
//85 = {ProgressStartEvent@2604} "ProgressStart Resolve artifact commons-collections.jar (commons-collections:commons-collections:3.2.1)"
//86 = {ProgressCompleteEvent@2605} "ProgressComplete "
//87 = {ProgressStartEvent@2606} "ProgressStart Resolve artifact commons-lang.jar (commons-lang:commons-lang:2.4)"
//88 = {ProgressCompleteEvent@2607} "ProgressComplete "
//89 = {ProgressStartEvent@2608} "ProgressStart Resolve artifact ezmorph.jar (net.sf.ezmorph:ezmorph:1.0.6)"
//90 = {ProgressCompleteEvent@2609} "ProgressComplete "
//91 = {ProgressStartEvent@2610} "ProgressStart Resolve artifact xercesImpl.jar (xerces:xercesImpl:2.9.1)"
//92 = {ProgressCompleteEvent@2611} "ProgressComplete "
//93 = {ProgressStartEvent@2612} "ProgressStart Resolve artifact ant-launcher.jar (org.apache.ant:ant-launcher:1.8.0)"
//94 = {ProgressCompleteEvent@2613} "ProgressComplete "
//95 = {ProgressStartEvent@2614} "ProgressStart Resolve artifact maven-repository-metadata.jar (org.apache.maven:maven-repository-metadata:2.2.1)"
//96 = {ProgressCompleteEvent@2615} "ProgressComplete "
//97 = {ProgressStartEvent@2616} "ProgressStart Resolve artifact backport-util-concurrent.jar (backport-util-concurrent:backport-util-concurrent:3.1)"
//98 = {ProgressCompleteEvent@2617} "ProgressComplete "
//99 = {ProgressStartEvent@2618} "ProgressStart Resolve artifact maven-profile.jar (org.apache.maven:maven-profile:2.2.1)"
//100 = {ProgressCompleteEvent@2731} "ProgressComplete "
//101 = {ProgressStartEvent@2732} "ProgressStart Resolve artifact maven-plugin-registry.jar (org.apache.maven:maven-plugin-registry:2.2.1)"
//102 = {ProgressCompleteEvent@2733} "ProgressComplete "
//103 = {ProgressStartEvent@2734} "ProgressStart Resolve artifact wagon-http-shared.jar (org.apache.maven.wagon:wagon-http-shared:1.0-beta-6)"
//104 = {ProgressCompleteEvent@2735} "ProgressComplete "
//105 = {ProgressStartEvent@2736} "ProgressStart Resolve artifact junit.jar (junit:junit:3.8.1)"
//106 = {ProgressCompleteEvent@2737} "ProgressComplete "
//107 = {ProgressStartEvent@2738} "ProgressStart Resolve artifact xercesMinimal.jar (nekohtml:xercesMinimal:1.9.6.2)"
//108 = {ProgressCompleteEvent@2739} "ProgressComplete "
//109 = {ProgressStartEvent@2740} "ProgressStart Resolve artifact nekohtml.jar (nekohtml:nekohtml:1.9.6.2)"
//110 = {ProgressCompleteEvent@2741} "ProgressComplete "
//111 = {ProgressStartEvent@2742} "ProgressStart Resolve artifact plexus-utils.jar (org.codehaus.plexus:plexus-utils:2.0.6)"
//112 = {ProgressCompleteEvent@2743} "ProgressComplete "
//113 = {ProgressCompleteEvent@2744} "ProgressComplete "
//114 = {ProgressCompleteEvent@2745} "ProgressComplete "
//115 = {ProgressCompleteEvent@2746} "ProgressComplete "
//116 = {ProgressStartEvent@2747} "ProgressStart Apply plugin com.gradle.plugin-publish to root project 'gradle-js-plugin'"
//117 = {ProgressStartEvent@2748} "ProgressStart Apply plugin org.gradle.api.plugins.JavaPlugin to root project 'gradle-js-plugin'"
//118 = {ProgressStartEvent@2749} "ProgressStart Apply plugin org.gradle.api.plugins.JavaBasePlugin to root project 'gradle-js-plugin'"
//119 = {ProgressStartEvent@2750} "ProgressStart Apply plugin org.gradle.api.plugins.BasePlugin to root project 'gradle-js-plugin'"
//120 = {ProgressStartEvent@2751} "ProgressStart Apply plugin org.gradle.language.base.plugins.LifecycleBasePlugin to root project 'gradle-js-plugin'"
//121 = {ProgressCompleteEvent@2752} "ProgressComplete "
//122 = {ProgressCompleteEvent@2753} "ProgressComplete "
//123 = {ProgressStartEvent@2754} "ProgressStart Apply plugin org.gradle.api.plugins.ReportingBasePlugin to root project 'gradle-js-plugin'"
//124 = {ProgressCompleteEvent@2755} "ProgressComplete "
//125 = {ProgressStartEvent@2756} "ProgressStart Apply plugin org.gradle.language.base.plugins.LanguageBasePlugin to root project 'gradle-js-plugin'"
//126 = {ProgressStartEvent@2757} "ProgressStart Apply plugin org.gradle.platform.base.plugins.ComponentBasePlugin to root project 'gradle-js-plugin'"
//127 = {ProgressCompleteEvent@2758} "ProgressComplete "
//128 = {ProgressCompleteEvent@2759} "ProgressComplete "
//129 = {ProgressStartEvent@2760} "ProgressStart Apply plugin org.gradle.platform.base.plugins.BinaryBasePlugin to root project 'gradle-js-plugin'"
//130 = {ProgressCompleteEvent@2761} "ProgressComplete "
//131 = {ProgressCompleteEvent@2762} "ProgressComplete "
//132 = {ProgressCompleteEvent@2763} "ProgressComplete "
//133 = {ProgressCompleteEvent@2764} "ProgressComplete "
//134 = {ProgressStartEvent@2765} "ProgressStart Apply plugin com.jfrog.bintray to root project 'gradle-js-plugin'"
//135 = {ProgressCompleteEvent@2766} "ProgressComplete "
//136 = {ProgressStartEvent@2767} "ProgressStart Apply plugin org.gradle.groovy to root project 'gradle-js-plugin'"
//137 = {ProgressStartEvent@2768} "ProgressStart Apply plugin org.gradle.api.plugins.GroovyBasePlugin to root project 'gradle-js-plugin'"
//138 = {ProgressCompleteEvent@2769} "ProgressComplete "
//139 = {ProgressCompleteEvent@2770} "ProgressComplete "
//140 = {ProgressStartEvent@2771} "ProgressStart Apply plugin org.gradle.maven to root project 'gradle-js-plugin'"
//141 = {ProgressCompleteEvent@2772} "ProgressComplete "
//142 = {ProgressStartEvent@2773} "ProgressStart Apply plugin org.gradle.maven-publish to root project 'gradle-js-plugin'"
//143 = {ProgressStartEvent@2774} "ProgressStart Apply plugin org.gradle.api.publish.plugins.PublishingPlugin to root project 'gradle-js-plugin'"
//144 = {ProgressCompleteEvent@2775} "ProgressComplete "
//145 = {ProgressCompleteEvent@2776} "ProgressComplete "
//146 = {ProgressStartEvent@2777} "ProgressStart Apply plugin org.gradle.signing to root project 'gradle-js-plugin'"
//147 = {ProgressCompleteEvent@2778} "ProgressComplete "
//148 = {ProgressStartEvent@2779} "ProgressStart Apply plugin org.gradle.jacoco to root project 'gradle-js-plugin'"
//149 = {ProgressCompleteEvent@2780} "ProgressComplete "
//150 = {ProgressStartEvent@2781} "ProgressStart Apply plugin org.gradle.project-report to root project 'gradle-js-plugin'"
//151 = {ProgressCompleteEvent@2782} "ProgressComplete "
//152 = {LogEvent@2783} "[WARN] [org.gradle.internal.featurelifecycle.LoggingDeprecatedFeatureHandler] The ConfigurableReport.setDestination(Object) method has been deprecated and is scheduled to be removed in Gradle 5.0. Please use the method ConfigurableReport.setDestination(File) instead.\n\tat org.gradle.api.reporting.internal.SimpleReport.setDestination(SimpleReport.java:76)\n\tat org.gradle.api.reporting.internal.TaskGeneratedSingleDirectoryReport_Decorated.setDestination(Unknown Source)\n\tat org.gradle.api.reporting.internal.TaskGeneratedSingleDirectoryReport_Decorated.destination(Unknown Source)\n\tat org.gradle.api.reporting.internal.TaskGeneratedSingleDirectoryReport_Decorated$destination.call(Unknown Source)\n\tat org.codehaus.groovy.runtime.callsite.CallSiteArray.defaultCall(CallSiteArray.java:48)\n\tat org.codehaus.groovy.runtime.callsite.AbstractCallSite.call(AbstractCallSite.java:113)\n\tat org.codehaus.groovy.runtime.callsite.AbstractCallSite.call(AbstractCallSite.java:125)\n\tat build_9biojo5uvzhtacqyqe68txy"
//153 = {ProgressCompleteEvent@2784} "ProgressComplete "
//154 = {ProgressCompleteEvent@2785} "ProgressComplete "
//155 = {ProgressEvent@2786} "Progress <=============> 100% CONFIGURING"
//156 = {ProgressCompleteEvent@2787} "ProgressComplete "
//157 = {ProgressCompleteEvent@2788} "ProgressComplete "
//158 = {ProgressStartEvent@2789} "ProgressStart Calculate task graph"
//159 = {ProgressCompleteEvent@2790} "ProgressComplete "
//160 = {ProgressStartEvent@2791} "ProgressStart Run tasks"
//161 = {ProgressCompleteEvent@2792} "ProgressComplete "
//162 = {ProgressStartEvent@2793} "ProgressStart EXECUTION PHASE"
//163 = {LogEvent@2794} "[WARN] [org.gradle.util.DeprecationLogger] Build cache is an incubating feature."
//164 = {LogEvent@2795} "[WARN] [org.gradle.caching.internal.BuildCacheServiceProvider] Using directory (/Users/ewendelin/.gradle/caches/build-cache-1) as local build cache, push is enabled."
//165 = {ProgressStartEvent@2796} "ProgressStart Task :compileJava"
//166 = {ProgressStartEvent@2797} "ProgressStart Execute :compileJava"
//167 = {ProgressCompleteEvent@2798} "ProgressComplete NO-SOURCE"
//168 = {ProgressEvent@2799} "Progress <======-------> 50% EXECUTING"
//169 = {ProgressCompleteEvent@2800} "ProgressComplete "
//170 = {ProgressStartEvent@2801} "ProgressStart Task :compileGroovy"
//171 = {ProgressStartEvent@2802} "ProgressStart Execute :compileGroovy"
//172 = {ProgressStartEvent@2803} "ProgressStart Resolve dependencies of :compileClasspath"
//173 = {ProgressStartEvent@2804} "ProgressStart Resolve dependencies ':compileClasspath'"
//174 = {ProgressCompleteEvent@2805} "ProgressComplete "
//175 = {ProgressCompleteEvent@2806} "ProgressComplete "
//176 = {ProgressStartEvent@2807} "ProgressStart Resolve artifacts view of :compileClasspath"
//177 = {ProgressStartEvent@2808} "ProgressStart Resolve artifact closure-compiler.jar (com.google.javascript:closure-compiler:v20160208)"
//178 = {ProgressStartEvent@2809} "ProgressStart Resolve artifact html2js.jar (io.jdev.html2js:html2js:0.1)"
//179 = {ProgressCompleteEvent@2810} "ProgressComplete "
//180 = {ProgressCompleteEvent@2811} "ProgressComplete "
//181 = {ProgressCompleteEvent@2812} "ProgressComplete "
//182 = {ProgressStartEvent@2813} "ProgressStart Resolve artifacts view of :compileClasspath"
//183 = {ProgressStartEvent@2814} "ProgressStart Resolve artifact html2js.jar (io.jdev.html2js:html2js:0.1)"
//184 = {ProgressStartEvent@2815} "ProgressStart Resolve artifact closure-compiler.jar (com.google.javascript:closure-compiler:v20160208)"
//185 = {ProgressCompleteEvent@2816} "ProgressComplete "
//186 = {ProgressCompleteEvent@2817} "ProgressComplete "
//187 = {ProgressCompleteEvent@2818} "ProgressComplete "
//188 = {ProgressStartEvent@2819} "ProgressStart Execute task action 1/3 for :compileGroovy"
//189 = {ProgressCompleteEvent@2820} "ProgressComplete "
//190 = {ProgressStartEvent@2821} "ProgressStart Execute task action 2/3 for :compileGroovy"
//191 = {ProgressCompleteEvent@2822} "ProgressComplete "
//192 = {ProgressStartEvent@2823} "ProgressStart Execute task action 3/3 for :compileGroovy"
//193 = {ProgressStartEvent@2824} "ProgressStart Resolve artifacts view of :compileClasspath"
//194 = {ProgressStartEvent@2825} "ProgressStart Resolve artifact closure-compiler.jar (com.google.javascript:closure-compiler:v20160208)"
//195 = {ProgressStartEvent@2826} "ProgressStart Resolve artifact html2js.jar (io.jdev.html2js:html2js:0.1)"
//196 = {ProgressCompleteEvent@2827} "ProgressComplete "
//197 = {ProgressCompleteEvent@2828} "ProgressComplete "
//198 = {ProgressCompleteEvent@2829} "ProgressComplete "
//199 = {ProgressStartEvent@2830} "ProgressStart Resolve artifacts view of :compileClasspath"
//200 = {ProgressStartEvent@2932} "ProgressStart Resolve artifact closure-compiler.jar (com.google.javascript:closure-compiler:v20160208)"
//201 = {ProgressCompleteEvent@2933} "ProgressComplete "
//202 = {ProgressStartEvent@2934} "ProgressStart Resolve artifact html2js.jar (io.jdev.html2js:html2js:0.1)"
//203 = {ProgressCompleteEvent@2935} "ProgressComplete "
//204 = {ProgressCompleteEvent@2936} "ProgressComplete "
//205 = {ProgressStartEvent@2937} "ProgressStart Resolve artifacts view of :compileClasspath"
//206 = {ProgressStartEvent@2938} "ProgressStart Resolve artifact closure-compiler.jar (com.google.javascript:closure-compiler:v20160208)"
//207 = {ProgressStartEvent@2939} "ProgressStart Resolve artifact html2js.jar (io.jdev.html2js:html2js:0.1)"
//208 = {ProgressCompleteEvent@2940} "ProgressComplete "
//209 = {ProgressCompleteEvent@2941} "ProgressComplete "
//210 = {ProgressCompleteEvent@2942} "ProgressComplete "
//211 = {ProgressStartEvent@2943} "ProgressStart Resolve artifacts view of :compileClasspath"
//212 = {ProgressStartEvent@2944} "ProgressStart Resolve artifact closure-compiler.jar (com.google.javascript:closure-compiler:v20160208)"
//213 = {ProgressCompleteEvent@2945} "ProgressComplete "
//214 = {ProgressStartEvent@2946} "ProgressStart Resolve artifact html2js.jar (io.jdev.html2js:html2js:0.1)"
//215 = {ProgressCompleteEvent@2947} "ProgressComplete "
//216 = {ProgressCompleteEvent@2948} "ProgressComplete "
//217 = {ProgressStartEvent@2949} "ProgressStart org.gradle.api.internal.tasks.compile.ApiGroovyCompiler"
//218 = {StyledTextOutputEvent@2950} "[ERROR] [system.err] <Normal>warning: [options] bootstrap class path not set in conjunction with -source 1.6\n</Normal>"
//219 = {StyledTextOutputEvent@2951} "[ERROR] [system.err] <Normal>1 warning\n</Normal>"
//220 = {ProgressCompleteEvent@2952} "ProgressComplete "
//221 = {ProgressCompleteEvent@2953} "ProgressComplete "
//222 = {ProgressStartEvent@2954} "ProgressStart Store entry b855a90b602d218cc8580ac3dfca8af4 in local build cache"
//223 = {ProgressCompleteEvent@2955} "ProgressComplete "
//224 = {ProgressCompleteEvent@2956} "ProgressComplete "
//225 = {ProgressEvent@2957} "Progress <=============> 100% EXECUTING"
//226 = {ProgressCompleteEvent@2958} "ProgressComplete "
//227 = {ProgressCompleteEvent@2959} "ProgressComplete "
//228 = {StyledTextOutputEvent@2960} "[LIFECYCLE] [org.gradle.internal.buildevents.BuildResultLogger] <Normal>\n</Normal>"
//229 = {StyledTextOutputEvent@2961} "[LIFECYCLE] [org.gradle.internal.buildevents.BuildResultLogger] <Success>BUILD SUCCESSFUL</Success><Normal> in 32s\n</Normal>"
//230 = {ProgressCompleteEvent@2962} "ProgressComplete "
//231 = {StyledTextOutputEvent@2963} "[LIFECYCLE] [org.gradle.internal.buildevents.BuildResultLogger] <Normal>1 actionable task: 1 executed, 0 avoided (0%)\n</Normal>"
//232 = {StyledTextOutputEvent@2964} "[LIFECYCLE] [com.gradle.scan.plugin.BuildScanPlugin] <Normal>\n</Normal>"
//233 = {StyledTextOutputEvent@2965} "[LIFECYCLE] [com.gradle.scan.plugin.BuildScanPlugin] <Normal>Publishing build information...\n</Normal>"
//234 = {StyledTextOutputEvent@2966} "[LIFECYCLE] [com.gradle.scan.plugin.BuildScanPlugin] <Identifier>https://e.grdev.net/s/777unwl7sxxlq</Identifier><Normal>\n</Normal>"
//235 = {StyledTextOutputEvent@2967} "[LIFECYCLE] [com.gradle.scan.plugin.BuildScanPlugin] <Normal>\n</Normal>"
//236 = {ProgressCompleteEvent@2968} "ProgressComplete "
//237 = {MaxWorkerCountChangeEvent@3019} "MaxWorkerCountChangeEvent 0"
//238 = {EndOutputEvent@3020}
