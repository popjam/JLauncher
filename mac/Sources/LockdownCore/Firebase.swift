import Foundation

/// Firebase Realtime Database over its plain REST API (no SDK, no auth): reads, writes and the
/// live event stream.
public struct Firebase: Sendable {
    public let base: String
    public let key: String

    public init(url: String, key: String) {
        var clean = url.trimmingCharacters(in: .whitespacesAndNewlines)
        while clean.hasSuffix("/") { clean.removeLast() }
        base = clean
        self.key = key.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// A pairing key as the phone makes it: 40 of a-k, m-n, p-z, 2-9.
    public static func validKey(_ key: String) -> Bool {
        key.count == 40 && key.allSatisfy { "abcdefghijkmnpqrstuvwxyz23456789".contains($0) }
    }

    public static func validURL(_ url: String) -> Bool {
        guard let u = URL(string: url.trimmingCharacters(in: .whitespacesAndNewlines)), u.scheme == "https",
              let host = u.host else { return false }
        return host.hasSuffix(".firebaseio.com") || host.hasSuffix(".firebasedatabase.app")
    }

    public func url(_ path: String) -> URL { URL(string: "\(base)/lockdown/\(key)/\(path).json")! }

    public func get(_ path: String) async throws -> Any? {
        let (data, response) = try await URLSession.shared.data(from: url(path))
        try Self.check(response)
        return try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
    }

    public func put(_ path: String, _ value: Any) async throws {
        var request = URLRequest(url: url(path))
        request.httpMethod = "PUT"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed])
        let (_, response) = try await URLSession.shared.data(for: request)
        try Self.check(response)
    }

    /// Adds `value` under `path` with a new key in time order (the Mac's requests)
    public func post(_ path: String, _ value: Any) async throws {
        try await send("POST", path, value)
    }

    /// Writes only the children named in `value` ("id/field": value), leaving others as they are
    public func patch(_ path: String, _ value: [String: Any]) async throws {
        try await send("PATCH", path, value)
    }

    private func send(_ method: String, _ path: String, _ value: Any) async throws {
        var request = URLRequest(url: url(path))
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed])
        let (_, response) = try await URLSession.shared.data(for: request)
        try Self.check(response)
    }

    public struct HTTPError: Error, CustomStringConvertible {
        public let code: Int
        public var description: String { code == 401 || code == 403 ? "The server refused" : "HTTP \(code)" }
    }

    static func check(_ response: URLResponse) throws {
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        if !(200..<300).contains(code) { throw HTTPError(code: code) }
    }

    /// Streams `path`: calls `onValue` with its whole value at the start and after every change.
    /// Returns when the stream ends; throws on errors. The caller reconnects.
    public func stream(_ path: String, onValue: @escaping @Sendable (Any?) -> Void) async throws {
        var request = URLRequest(url: url(path))
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        request.timeoutInterval = 90 // Firebase sends keep-alive every 30s
        let (bytes, response) = try await URLSession.shared.bytes(for: request)
        try Self.check(response)
        var tree = EventTree()
        var event = ""
        for try await line in bytes.lines {
            if line.hasPrefix("event:") {
                event = line.dropFirst(6).trimmingCharacters(in: .whitespaces)
            } else if line.hasPrefix("data:") {
                let data = line.dropFirst(5).trimmingCharacters(in: .whitespaces)
                switch event {
                case "put", "patch":
                    guard let body = try? JSONSerialization.jsonObject(with: Data(data.utf8), options: [.fragmentsAllowed]) as? [String: Any],
                          let at = body["path"] as? String else { continue }
                    tree.apply(event, at: at, body["data"])
                    onValue(tree.value)
                case "cancel", "auth_revoked":
                    throw HTTPError(code: 403)
                default:
                    break // keep-alive
                }
            }
        }
    }
}

/// The value being streamed, updated by "put" (replace at a path) and "patch" (merge at a path).
public struct EventTree {
    public private(set) var value: Any?

    public init() {}

    public mutating func apply(_ event: String, at path: String, _ data: Any?) {
        let keys = path.split(separator: "/").map(String.init)
        let data = data is NSNull ? nil : data
        value = Self.set(value, keys[...], data, merge: event == "patch")
    }

    static func set(_ node: Any?, _ keys: ArraySlice<String>, _ data: Any?, merge: Bool) -> Any? {
        guard let first = keys.first else {
            if merge, var old = node as? [String: Any], let new = data as? [String: Any] {
                for (k, v) in new { old[k] = v is NSNull ? nil : v }
                return old
            }
            return data
        }
        var dict = node as? [String: Any] ?? [:]
        // Arrays (like sites) arrive as arrays; treat them as dictionaries keyed by index to edit them
        if let array = node as? [Any] {
            dict = Dictionary(uniqueKeysWithValues: array.enumerated().map { (String($0.offset), $0.element) })
        }
        dict[first] = set(dict[first], keys.dropFirst(), data, merge: merge)
        return dict
    }
}
