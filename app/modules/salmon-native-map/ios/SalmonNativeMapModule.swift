import ExpoModulesCore

public final class SalmonNativeMapModule: Module {
  public func definition() -> ModuleDefinition {
    Name("SalmonNativeMap")

    View(NativeRouteMapHostView.self) {
      Events("onMapEvent")

      AsyncFunction("handleMessage") { (view: NativeRouteMapHostView, message: String) in
        view.handleMessage(message)
      }

      AsyncFunction("reset") { (view: NativeRouteMapHostView) in
        view.reset()
      }
    }
  }
}
