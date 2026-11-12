import Flutter
import UIKit
import UserNotifications

/// 将 iOS APNs 注册、通知点击和权限回调转发到 Flutter 通道。
public final class PolyglotImPlugin: NSObject, FlutterPlugin, FlutterStreamHandler, FlutterSceneLifeCycleDelegate {
    private var sink: FlutterEventSink?
    private var foregroundApp: String?
    private var foregroundUser: String?
    private var foregroundEnabled = false
    private var foregroundSound = true
    private var token: String?
    private var initial: [String: Any]?

    /// 向 Flutter 注册方法/事件通道和应用生命周期委托，由引擎持有插件。
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

    /// 处理 Dart 发起的权限、token、冷启动通知与角标请求，未知方法返回未实现。
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
        case "configureForeground":
            let options = call.arguments as? [String: Any] ?? [:]
            foregroundApp = options["app_id"] as? String
            foregroundUser = options["user_id"] as? String
            foregroundEnabled = options["enabled"] as? Bool ?? false
            foregroundSound = options["sound"] as? Bool ?? true
            result(nil)
        case "currentToken": result(token)
        case "initialNotification": result(initial); initial = nil
        case "setBadge": UIApplication.shared.applicationIconBadgeNumber = max(0, call.arguments as? Int ?? 0); result(nil)
        default: result(FlutterMethodNotImplemented)
        }
    }

    /// 保存当前 Flutter 事件接收器，供后续 token 与通知回调使用。
    public func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        sink = events
        if let token = token { events(["type": "token", "token": token]) }
        return nil
    }
    /// 解绑事件接收器，不再向已取消的 Flutter 订阅发送回调。
    public func onCancel(withArguments arguments: Any?) -> FlutterError? { sink = nil; return nil }

    /// 保存启动通知；系统已授权时重新登记 APNs，供 Flutter 恢复 token 和点击上下文。
    public func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any] = [:]) -> Bool {
        if let info = launchOptions[.remoteNotification] as? [AnyHashable: Any] { initial = notification(info, type: "opened") }
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            if settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional {
                DispatchQueue.main.async { application.registerForRemoteNotifications() }
            }
        }
        return true
    }
    /// 从场景连接选项中提取冷启动通知，供 Flutter 完成登录后恢复导航。
    public func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions?) -> Bool {
        if let response = connectionOptions?.notificationResponse, let event = notification(response.notification.request.content.userInfo, type: "opened") {
            if let sink = sink { sink(event) } else { initial = event }
            return true
        }
        return false
    }
    /// 将 APNs token 转为十六进制文本并通知当前 Flutter 订阅。
    public func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        token = deviceToken.map { String(format: "%02x", $0) }.joined()
        sink?(["type": "token", "token": token!])
    }
    /// 将 APNs 注册失败转为 APNS_REGISTRATION 错误事件，不伪造可用 token。
    public func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        sink?(FlutterError(code: "APNS_REGISTRATION", message: error.localizedDescription, details: nil))
    }
    private func notification(_ info: [AnyHashable: Any], type: String) -> [String: Any]? {
        guard let app = info["app_id"] as? String, let user = info["user_id"] as? String, let message = info["message_id"] as? String else { return nil }
        return ["type": type, "app_id": app, "user_id": user, "message_id": message]
    }
    /// 前台通知始终提示 Flutter 同步；仅当前绑定账号按显式策略展示系统提醒。
    public func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        if let event = self.notification(notification.request.content.userInfo, type: "foreground") {
            DispatchQueue.main.async {
                self.sink?(event)
                let matches = event["app_id"] as? String == self.foregroundApp && event["user_id"] as? String == self.foregroundUser
                var options: UNNotificationPresentationOptions = []
                if matches && self.foregroundEnabled {
                    if #available(iOS 14.0, *) { options = [.banner, .list, .badge] }
                    else { options = [.alert, .badge] }
                    if self.foregroundSound { options.insert(.sound) }
                }
                completionHandler(options)
            }
        } else { completionHandler([.alert, .sound, .badge]) }
    }
    /// 将通知点击发给 Flutter；尚无订阅时保存为初始通知，最后完成系统回调。
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
