package androidx.compose.compiler.plugins.kotlin

import org.jetbrains.kotlin.name.Name

object ComposeNames {
  val COMPOSER = Name.identifier("composer")
  val COMPOSER_PARAMETER = Name.identifier("\$composer")
  val CHANGED_PARAMETER = Name.identifier("\$changed")
  val FORCE_PARAMETER = Name.identifier("\$force")
  val DEFAULT_PARAMETER = Name.identifier("\$default")
  val STABILITY_FLAG = Name.identifier("\$stable")
  val STABILITY_PROP_FLAG = Name.identifier("\$stableprop")
  val STABILITY_GETTER_FLAG = "\$stableprop_getter"
  val JOIN_KEY = Name.identifier("joinKey")
  val START_RESTART_GROUP = Name.identifier("startRestartGroup")
  val END_RESTART_GROUP = Name.identifier("endRestartGroup")
  val UPDATE_SCOPE = Name.identifier("updateScope")
  val SOURCE_INFORMATION = "sourceInformation"
  val SOURCE_INFORMATION_MARKER_START = "sourceInformationMarkerStart"
  val IS_TRACE_IN_PROGRESS = "isTraceInProgress"
  val TRACE_EVENT_START = "traceEventStart"
  val TRACE_EVENT_END = "traceEventEnd"
  val SOURCE_INFORMATION_MARKER_END = "sourceInformationMarkerEnd"
  val UPDATE_CHANGED_FLAGS = "updateChangedFlags"
  val CURRENT_MARKER = Name.identifier("currentMarker")
  val END_TO_MARKER = Name.identifier("endToMarker")
  val REMEMBER_COMPOSABLE_LAMBDA = "rememberComposableLambda"
  val REMEMBER_COMPOSABLE_LAMBDAN = "rememberComposableLambdaN"
  val DEFAULT_IMPLS = Name.identifier("ComposeDefaultImpls")
  val SHOULD_EXECUTE = Name.identifier("shouldExecute")
}
