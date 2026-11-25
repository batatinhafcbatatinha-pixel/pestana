//! Pitch detection for the tuner: McLeod Pitch Method (normalized square
//! difference function) with parabolic peak interpolation. Sub-cent accurate
//! on clean signals and robust to the strong overtones of plucked strings.

pub struct Pitch {
    pub freq: f32,
    /// 0..1, how periodic the signal is (NSDF peak height).
    pub clarity: f32,
    /// Root mean square level of the frame.
    pub rms: f32,
}

pub struct Detector {
    nsdf: Vec<f64>,
    buf: Vec<f64>,
}

impl Default for Detector {
    fn default() -> Self {
        Self::new()
    }
}

impl Detector {
    pub fn new() -> Detector {
        Detector { nsdf: Vec::new(), buf: Vec::new() }
    }

    /// `min_hz`/`max_hz` bound the search (e.g. 25..1600 Hz for guitar and bass).
    pub fn detect(&mut self, samples: &[f32], sample_rate: f32, min_hz: f32, max_hz: f32) -> Pitch {
        let n = samples.len();
        let none = |rms| Pitch { freq: -1.0, clarity: 0.0, rms };
        if n < 64 || sample_rate <= 0.0 {
            return none(0.0);
        }
        // Remove DC and compute level.
        let mean = samples.iter().map(|&x| x as f64).sum::<f64>() / n as f64;
        self.buf.clear();
        self.buf.extend(samples.iter().map(|&x| x as f64 - mean));
        let x = &self.buf;
        let energy: f64 = x.iter().map(|v| v * v).sum();
        let rms = (energy / n as f64).sqrt() as f32;
        if rms < 1e-4 {
            return none(rms);
        }

        let min_lag = ((sample_rate / max_hz).floor() as usize).max(2);
        let max_lag = ((sample_rate / min_hz).ceil() as usize).min(n / 2);
        if max_lag <= min_lag + 2 {
            return none(rms);
        }

        // NSDF(tau) = 2 * r(tau) / m(tau), m computed incrementally.
        self.nsdf.clear();
        self.nsdf.resize(max_lag + 1, 0.0);
        let mut m = 2.0 * energy;
        for tau in 0..=max_lag {
            if tau > 0 {
                m -= x[tau - 1] * x[tau - 1] + x[n - tau] * x[n - tau];
            }
            let len = n - tau;
            let (a, b) = (&x[..len], &x[tau..tau + len]);
            let mut r = 0.0f64;
            // 4-way unrolled dot product; the compiler vectorizes this well.
            let chunks = len / 4;
            let (mut r0, mut r1, mut r2, mut r3) = (0.0, 0.0, 0.0, 0.0);
            for k in 0..chunks {
                let i = k * 4;
                r0 += a[i] * b[i];
                r1 += a[i + 1] * b[i + 1];
                r2 += a[i + 2] * b[i + 2];
                r3 += a[i + 3] * b[i + 3];
            }
            r += r0 + r1 + r2 + r3;
            for i in chunks * 4..len {
                r += a[i] * b[i];
            }
            self.nsdf[tau] = if m > 1e-12 { 2.0 * r / m } else { 0.0 };
        }
        let d = &self.nsdf;

        // Key maxima: highest point of each positive lobe after the first
        // negative-going zero crossing.
        let mut peaks: Vec<(f64, f64)> = Vec::new(); // (lag, value), interpolated
        let mut tau = 1;
        while tau < max_lag && d[tau] > 0.0 {
            tau += 1;
        }
        while tau < max_lag {
            while tau < max_lag && d[tau] <= 0.0 {
                tau += 1;
            }
            let mut best = tau;
            while tau < max_lag && d[tau] > 0.0 {
                if d[tau] > d[best] {
                    best = tau;
                }
                tau += 1;
            }
            if best >= min_lag && best > 0 && best < max_lag && d[best] > 0.0 && tau < max_lag {
                peaks.push(parabolic(d, best));
            } else if best >= min_lag && best > 0 && best < max_lag && d[best] > 0.0 {
                // Lobe running into the search limit still counts if it peaked.
                if d[best] >= d[best - 1] && d[best] >= d[best + 1] {
                    peaks.push(parabolic(d, best));
                }
            }
        }
        if peaks.is_empty() {
            return none(rms);
        }
        let highest = peaks.iter().map(|p| p.1).fold(f64::MIN, f64::max);
        let threshold = 0.9 * highest;
        let (lag, val) = *peaks.iter().find(|p| p.1 >= threshold).unwrap();
        Pitch { freq: (sample_rate as f64 / lag) as f32, clarity: val.min(1.0) as f32, rms }
    }
}

fn parabolic(d: &[f64], i: usize) -> (f64, f64) {
    let (a, b, c) = (d[i - 1], d[i], d[i + 1]);
    let den = a - 2.0 * b + c;
    if den.abs() < 1e-12 {
        return (i as f64, b);
    }
    let delta = 0.5 * (a - c) / den;
    (i as f64 + delta, b - 0.25 * (a - c) * delta)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::f64::consts::PI;

    fn string_tone(f: f64, sr: f64, n: usize, harmonics: &[f64]) -> Vec<f32> {
        (0..n)
            .map(|i| {
                let t = i as f64 / sr;
                harmonics.iter().enumerate().map(|(h, a)| a * (2.0 * PI * f * (h + 1) as f64 * t).sin()).sum::<f64>() as f32 * 0.3
            })
            .collect()
    }

    fn cents(a: f64, b: f64) -> f64 {
        1200.0 * (a / b).log2()
    }

    #[test]
    fn accurate_across_guitar_range() {
        let sr = 48000.0;
        let mut det = Detector::new();
        // Low B (5-string bass) up to high E 12th fret, with guitar-like overtones
        // where the second harmonic is louder than the fundamental.
        for &f in &[30.87, 41.2, 61.74, 73.42, 82.41, 110.0, 146.83, 196.0, 246.94, 329.63, 440.0, 659.26, 987.77] {
            let x = string_tone(f, sr, 8192, &[0.6, 1.0, 0.5, 0.3, 0.2, 0.1]);
            let p = det.detect(&x, sr as f32, 25.0, 1600.0);
            let err = cents(p.freq as f64, f).abs();
            assert!(err < 0.5, "{f} Hz -> {} Hz ({err:.3} cents)", p.freq);
            assert!(p.clarity > 0.9);
        }
    }

    #[test]
    fn sub_cent_on_detuned_notes() {
        let sr = 48000.0;
        let mut det = Detector::new();
        for &c in &[-7.3, -1.1, 0.4, 2.5, 13.0] {
            let f = 110.0 * 2f64.powf(c / 1200.0);
            let x = string_tone(f, sr, 4096, &[1.0, 0.7, 0.4]);
            let p = det.detect(&x, sr as f32, 50.0, 1600.0);
            let got = cents(p.freq as f64, 110.0);
            assert!((got - c).abs() < 0.3, "wanted {c} got {got:.3}");
        }
    }

    #[test]
    fn silence_and_noise_rejected() {
        let mut det = Detector::new();
        let silent = vec![0.0f32; 4096];
        assert!(det.detect(&silent, 48000.0, 50.0, 1600.0).freq < 0.0);
        // Deterministic white noise.
        let mut s = 12345u32;
        let noise: Vec<f32> = (0..4096)
            .map(|_| {
                s = s.wrapping_mul(1664525).wrapping_add(1013904223);
                (s >> 8) as f32 / (1 << 24) as f32 - 0.5
            })
            .collect();
        let p = det.detect(&noise, 48000.0, 50.0, 1600.0);
        assert!(p.freq < 0.0 || p.clarity < 0.5, "noise clarity {}", p.clarity);
    }
}
