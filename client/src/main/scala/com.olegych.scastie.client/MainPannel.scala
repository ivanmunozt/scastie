package com.olegych.scastie
package client

import App._

import japgolly.scalajs.react._, vdom.all._

import org.scalajs.dom.raw.HTMLPreElement

object MainPannel {

  private val consoleElement = Ref[HTMLPreElement]("console")

  private val component =
    ReactComponentB[(State, Backend, Props)]("MainPannel").render_P {
      case (state, backend, props) =>
        def show(view: View) = {
          if (view == state.view) TagMod(display.block)
          else TagMod(display.none)
        }

        val theme = if (state.isDarkTheme) "dark" else "light"

        val consoleCss =
          if (state.consoleIsOpen) "with-console"
          else ""

        val embedded = props.embedded.isDefined

        val embeddedMenu =
          if (embedded) TagMod(EmbeddedMenu(state, backend))
          else EmptyTag

        def toogleShowHelpAtStartup(e: ReactEvent): Callback = {
          backend.toggleHelpAtStartup()
        }

        def closeHelp(e: ReactEvent): Callback = {
          backend.closeHelp()
        }

        val showHelp = 
          if(state.isShowingHelpAtStartup && state.isStartup && !embedded) true
          else !state.isHelpModalClosed

        val helpClosePannel =
          if(showHelp) {
            TagMod(
              div(`class` := "help-close")(
                button(onClick ==> closeHelp)("Close"),
                div(`class` := "not-again")(
                  p("Dont show again"),
                  input.checkbox(onChange ==> toogleShowHelpAtStartup, checked := !state.isShowingHelpAtStartup)
                )
              )
            )
          } else EmptyTag

        val helpState =
          if (showHelp) {
            val helpModal = 
              api.Instrumentation(api.Position(0, 0), api.runtime.help.copy(folded = false))

            state.copy(
              outputs = state.outputs.copy(instrumentations = state.outputs.instrumentations + helpModal)
            )
          } else state

        div(`class` := "main-pannel")(
          TopBar(state, backend),
          div(`class` := s"pannel $theme $consoleCss", show(View.Editor))(
            helpClosePannel,
            Editor(helpState, backend),
            embeddedMenu,
            pre(`class` := "output-console", ref := consoleElement)(
              state.outputs.console.mkString("")
            )
          ),
          div(`class` := s"pannel $theme", show(View.Libraries))(
            Libraries(state, backend)),
          div(`class` := s"pannel $theme", show(View.UserProfile))(
            UserProfile(props.router, state.view))
        )
    }.componentDidUpdate(scope =>
        Callback {
          val consoleDom = consoleElement(scope.$).get
          consoleDom.scrollTop = consoleDom.scrollHeight.toDouble
      })
      .build

  def apply(state: State, backend: Backend, props: Props) =
    component((state, backend, props))
}
