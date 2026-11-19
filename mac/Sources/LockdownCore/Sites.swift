import Foundation

/// Website matching, the same as the phone's `Limits.host` / `Limits.matches`.
public enum Sites {

    /// The site in an address bar's text, or nil if it isn't one.
    /// "https://www.reddit.com/r/x" and "reddit.com. Search or enter address" are both "reddit.com".
    public static func host(_ address: String) -> String? {
        var text = address.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if let space = text.firstIndex(where: { $0 == " " }) { text = String(text[..<space]) }
        if let scheme = text.range(of: "://") { text = String(text[scheme.upperBound...]) }
        if let cut = text.firstIndex(where: { "/?#:".contains($0) }) { text = String(text[..<cut]) }
        while text.hasSuffix(".") { text.removeLast() }
        if text.hasPrefix("www.") { text.removeFirst(4) }
        if text.hasPrefix("m.") { text.removeFirst(2) }
        return text.contains(".") && text.count > 3 ? text : nil
    }

    /// Whether `host` is `site`, loosely: its subdomains (old.reddit.com), the same name under another
    /// ending (reddit.co.uk), and names that start with it (redditmedia.com). Names under 4 letters
    /// (x.com) only match exactly, so they don't catch others (xbox.com).
    public static func matches(_ host: String, _ site: String) -> Bool {
        var s = site.trimmingCharacters(in: .whitespaces).lowercased()
        if s.hasPrefix("www.") { s.removeFirst(4) }
        while s.hasSuffix(".") { s.removeLast() }
        var h = host.trimmingCharacters(in: .whitespaces).lowercased()
        while h.hasSuffix(".") { h.removeLast() }
        if s.isEmpty { return false }
        if h == s || h.hasSuffix("." + s) { return true }
        guard let name = siteName(s), let hostName = siteName(h) else { return false }
        return hostName == name || (name.count >= 4 && hostName.hasPrefix(name))
    }

    /// The name a domain is registered under: "reddit" for old.reddit.com and reddit.co.uk.
    public static func siteName(_ domain: String) -> String? {
        let labels = domain.split(separator: ".").map(String.init)
        if labels.count < 2 { return nil }
        let twoPart = labels.count >= 3 && labels[labels.count - 1].count == 2
            && ["co", "com", "org", "net", "ac", "gov", "edu"].contains(labels[labels.count - 2])
        return labels[labels.count - (twoPart ? 3 : 2)]
    }

    /// Whether `host` is any of `sites`.
    public static func blocked(_ host: String, by sites: [String]) -> Bool {
        sites.contains { matches(host, $0) }
    }
}
