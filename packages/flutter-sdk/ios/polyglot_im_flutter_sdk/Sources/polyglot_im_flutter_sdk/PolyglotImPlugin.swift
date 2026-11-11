import Flutter
import UIKit
import UserNotifications

public final class PolyglotImPlugin: NSObject, FlutterPlugin, FlutterStreamHandler, FlutterSceneLifeCycleDelegate {
    private var sink: FlutterEventSink?
    private var token: String?
    private var initial: [String: Any]?

    public static func register(with registrar: FlutterPluginRegistrar) {
        let instance = PolyglotImPlugin()
        registrar.addMethodCallDelegate(instance, channel: FlutterMethodChannel(name: "polyglot_im/apns", binaryMessenger: registrar.messenger()))
        FlutterEventChannel(name: "polyglot_im/apns_events", binaryMessenger: registrar.messenger()).setStreamHandler(instance)
        registrar.addApplicationDelegate(instance)
        registrar.addSceneDelegate(instance)
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            if settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional {
                DispatchQueue.main.async { UIApplication.shared.registerForRemoteNotifications() }
            }
        }
    }

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "requestPermission":
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { allowed, error in
                DispatchQueue.main.async {
                    if let error = error { result(FlutterError(code: "APNS_PERMISSION", message: error.localizedDescription, details: nil)); return }
                    if allowed { UIApplication.shared.registerForRemoteNotifications() }
                    result(allowed)
                }
            }
        case "currentToken": result(token)
        case "initialNotification": result(initial); initial = nil
        case "setBadge": UIApplication.shared.applicationIconBadgeNumber = max(0, call.arguments as? Int ?? 0); result(nil)
        default: result(FlutterMethodNotImplemented)
        }
    }

    public func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        sink = events
        if let token = token { events(["type": "token", "token": token]) }
        return nil
    }
    public func onCancel(withArguments arguments: Any?) -> FlutterError? { sink = nil; return nil }

    public func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any] = [:]) -> Bool {
        if let info = launchOptions[.remoteNotification] as? [AnyHashable: Any] { initial = notification(info, type: "opened") }
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            if settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional {
                DispatchQueue.main.async { application.registerForRemoteNotifications() }
            }
        }
        return true
    }
    public func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions?) -> Bool {
        if let response = connectionOptions?.notificationResponse, let event = notification(response.notification.request.content.userInfo, type: "opened") {
            if let sink = sink { sink(event) } else { initial = event }
            return true
        }
        return false
    }
    public func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        token = deviceToken.map { String(format: "%02x", $0) }.joined()
        sink?(["type": "token", "token": token!])
    }
    public func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        sink?(FlutterError(code: "APNS_REGISTRATION", message: error.localizedDescription, details: nil))
    }
    private func notification(_ info: [AnyHashable: Any], type: String) -> [String: Any]? {
        guard let app = info["app_id"] as? String, let user = info["user_id"] as? String, let message = info["message_id"] as? String else { return nil }
        return ["type": type, "app_id": app, "user_id": user, "message_id": message]
    }
    public func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        if let event = self.notification(notification.request.content.userInfo, type: "foreground") {
            DispatchQueue.main.async {
                self.sink?(event)
                completionHandler([])
            }
        } else { completionHandler([.alert, .sound, .badge]) }
    }
    public func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void) {
        let event = notification(response.notification.request.content.userInfo, type: "opened")
        DispatchQueue.main.async {
            if let event = event {
                if let sink = self.sink { sink(event) } else { self.initial = event }
            }
            completionHandler()
        }
    }
}
