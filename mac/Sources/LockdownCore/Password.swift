import CryptoKit
import Foundation

/// The phone's `PasswordHelper`: lowercase hex SHA-256 of the UTF-8 password, no salt.
public enum Password {
    public static func hash(_ password: String) -> String {
        SHA256.hash(data: Data(password.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    public static func verify(_ input: String, hash stored: String) -> Bool {
        !stored.isEmpty && hash(input) == stored
    }
}
