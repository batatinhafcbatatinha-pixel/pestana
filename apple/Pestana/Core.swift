import Foundation

/// Calls into the Rust core (core/src/ffi.rs), the same code the Android app uses.
enum Core {
    private static func take(_ p: UnsafeMutablePointer<CChar>?) -> String {
        guard let p else { return "" }
        defer { pestana_free_string(p) }
        return String(cString: p)
    }

    static func extractPage(_ html: String, url: String) -> String {
        take(pestana_extract_page(html, url))
    }

    static func playlistLinks(_ html: String, url: String) -> String {
        take(pestana_playlist_links(html, url))
    }

    /// Artist, discography or album page -> {name, songs:[{id,label}], albums:[{slug,name,year,kind}]}
    static func artistPage(_ html: String, url: String) -> String {
        take(pestana_artist_page(html, url))
    }

    static func render(
        _ text: String, key: String, transpose: Int, simplify: Bool, view: Int,
        showTabs: Bool, accidentals: Int, cols: Int
    ) -> String {
        take(pestana_render(text, key, Int32(transpose), simplify, Int32(view), showTabs, Int32(accidentals), Int32(cols)))
    }

    static func chordShape(_ name: String) -> String {
        take(pestana_chord_shape(name))
    }

    static func bassShape(_ name: String) -> String {
        take(pestana_bass_shape(name))
    }
}

/// McLeod pitch detector (Rust). Not thread-safe: use from one queue.
final class PitchDetector {
    private let ptr: OpaquePointer?

    init() { ptr = pestana_detector_new() }
    deinit { pestana_detector_free(ptr) }

    /// Returns (frequency or -1, clarity 0...1, rms).
    func detect(_ samples: [Float], sampleRate: Int, minHz: Float, maxHz: Float) -> (Float, Float, Float) {
        var out = [Float](repeating: 0, count: 3)
        samples.withUnsafeBufferPointer { buf in
            out.withUnsafeMutableBufferPointer { o in
                pestana_detect(ptr, buf.baseAddress, Int32(buf.count), Int32(sampleRate), minHz, maxHz, o.baseAddress)
            }
        }
        return (out[0], out[1], out[2])
    }
}
