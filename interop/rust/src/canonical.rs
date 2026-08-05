//! A THIRD independent canonical encoder over the same corpus.
//!
//! `interop/test_canonical_bytes.py` asks whether boring and Python's cbor2
//! emit the identical octet sequence for a deterministic encoding. Two
//! implementations agreeing is much better than one; three is much better than
//! two, because the failure that survives a two-way check is the one where the
//! second implementation is wrong in the same direction as the first. That is
//! not hypothetical here: cbor2 and boring BOTH normalise every NaN to
//! `f97e00`, so the Python gate can never see it, and ciborium does not.
//!
//!     cargo run --quiet --manifest-path interop/rust/Cargo.toml \
//!         --bin canonical-bytes -- interop/canonical_fixture.cbor
//!
//! WHAT CIBORIUM PROVIDES, since "can Rust do this job at all" is the first
//! question this file answers -- and the answer is yes, with one caveat:
//!
//!   * Shortest float. `ciborium_ll`'s `Header::Float` picks binary16 when
//!     `f64::from(f16::from_f64(v)).to_bits() == v.to_bits()`, else binary32
//!     on the same test, else binary64. RFC 8949 4.1 preferred serialization
//!     decided on BITS, so unlike cbor2 it does not flatten NaN payloads or
//!     the NaN sign bit.
//!   * Shortest integer and length heads, in `Title::from(Header)`.
//!   * `ciborium::ser::into_writer`, which is what actually produces the bytes
//!     compared below. Every octet in this checker's output is ciborium's.
//!
//! THE CAVEAT, and why maps are not sorted with ciborium's own comparator:
//! `ciborium::value::CanonicalValue` exists and documents itself as RFC 7049
//! 3.9 / RFC 8949 4.2.3 length-first ordering, but `Integer::canonical_cmp`
//! computes a negative integer's encoded length from the two's-complement
//! width of the VALUE instead of of the CBOR argument `-1-n`. So it believes
//! -256 is three bytes where ciborium itself writes `38ff`, two:
//!
//!     -256 -> 38ff   (2 B)      1000 -> 1903e8 (3 B)
//!     by serialized bytes:      -256 sorts FIRST
//!     by CanonicalValue:        -256 sorts LAST
//!
//! That is ciborium disagreeing with ciborium, the same shape of finding as
//! cbor2's C extension disagreeing with cbor2's Python. So `canonicalize` here
//! orders by the LENGTH AND BYTES OF CIBORIUM'S OWN SERIALIZATION -- which is
//! what RFC 8949 4.2.3 literally says, and what ciborium's own private
//! `serialized_canonical_cmp` does -- and `CanonicalValue` is run as a second
//! pass whose disagreements are counted and printed, not obeyed.
//!
//! Ordering inside tag 258 is nobody's standard: RFC 8949 4.2 covers map keys
//! and tag 258 is not core CBOR. boring, cbor2 and this file all reuse the
//! map-key rule for set elements. That is a shared convention, not conformance.

use std::cmp::Ordering;
use std::collections::BTreeMap;
use std::process::ExitCode;

use ciborium::value::{CanonicalValue, Value};

fn hexs(b: &[u8]) -> String {
    let mut s = String::new();
    for (i, x) in b.iter().enumerate() {
        if i == 48 {
            s.push_str(&format!("...({} B)", b.len()));
            break;
        }
        s.push_str(&format!("{:02x}", x));
    }
    s
}

fn enc(v: &Value) -> Vec<u8> {
    let mut b = Vec::new();
    ciborium::ser::into_writer(v, &mut b).expect("ciborium failed to serialize a Value");
    b
}

/// RFC 8949 4.2.3, applied to the octets ciborium produced: shorter first,
/// then bytewise. Two lines, and the corpus contains the boundary cases that
/// would expose them if they were the wrong two lines.
fn cmp_len_first(a: &[u8], b: &[u8]) -> Ordering {
    match a.len().cmp(&b.len()) {
        Ordering::Equal => a.cmp(b),
        x => x,
    }
}

/// How to decide the order of map keys and set elements.
#[derive(Clone, Copy, PartialEq)]
enum Order {
    /// ciborium's serialized bytes, length first. The primary check.
    SerializedBytes,
    /// `ciborium::value::CanonicalValue`. Reported, not obeyed -- see above.
    CanonicalValueOrd,
}

fn canonicalize(v: &Value, order: Order) -> Value {
    match v {
        Value::Map(entries) => {
            let mut sorted: Vec<(Value, Value)> = entries
                .iter()
                .map(|(k, x)| (canonicalize(k, order), canonicalize(x, order)))
                .collect();
            match order {
                Order::SerializedBytes => {
                    sorted.sort_by(|a, b| cmp_len_first(&enc(&a.0), &enc(&b.0)))
                }
                Order::CanonicalValueOrd => sorted.sort_by(|a, b| {
                    CanonicalValue::from(a.0.clone()).cmp(&CanonicalValue::from(b.0.clone()))
                }),
            }
            Value::Map(sorted)
        }
        Value::Array(items) => Value::Array(items.iter().map(|e| canonicalize(e, order)).collect()),
        Value::Tag(258, inner) => match inner.as_ref() {
            Value::Array(items) => {
                let mut sorted: Vec<Value> = items.iter().map(|e| canonicalize(e, order)).collect();
                match order {
                    Order::SerializedBytes => {
                        sorted.sort_by(|a, b| cmp_len_first(&enc(a), &enc(b)))
                    }
                    Order::CanonicalValueOrd => sorted.sort_by(|a, b| {
                        CanonicalValue::from(a.clone()).cmp(&CanonicalValue::from(b.clone()))
                    }),
                }
                Value::Tag(258, Box::new(Value::Array(sorted)))
            }
            other => Value::Tag(258, Box::new(canonicalize(other, order))),
        },
        Value::Tag(t, inner) => Value::Tag(*t, Box::new(canonicalize(inner, order))),
        other => other.clone(),
    }
}

fn as_usize(v: &Value) -> Option<usize> {
    match v {
        Value::Integer(i) => usize::try_from(i128::from(*i)).ok(),
        _ => None,
    }
}

/// `["rep" kind n unit]` -> the value it stands for. See
/// `interop/gen_canonical_fixture.clj`; this exists so the 64 KiB
/// length-header boundary cases do not put a megabyte in a committed fixture.
fn expand_value(spec: &Value) -> Option<Value> {
    let a = match spec {
        Value::Array(a) if a.len() == 4 => a,
        _ => return None,
    };
    let n = as_usize(&a[2])?;
    match (&a[1], &a[3]) {
        (Value::Text(kind), Value::Text(u)) if kind == "text" => Some(Value::Text(u.repeat(n))),
        (Value::Text(kind), unit) if kind == "bytes" => {
            let b = u8::try_from(as_usize(unit)?).ok()?;
            Some(Value::Bytes(vec![b; n]))
        }
        (Value::Text(kind), unit) if kind == "array" => Some(Value::Array(vec![unit.clone(); n])),
        _ => None,
    }
}

/// `["rep-enc" head unit n]` -> `head ++ (unit * n)`.
fn expand_enc(spec: &Value) -> Option<Vec<u8>> {
    let a = match spec {
        Value::Array(a) if a.len() == 4 => a,
        _ => return None,
    };
    let (head, unit) = match (&a[1], &a[2]) {
        (Value::Bytes(h), Value::Bytes(u)) => (h, u),
        _ => return None,
    };
    let n = as_usize(&a[3])?;
    let mut out = head.clone();
    out.reserve(unit.len() * n);
    for _ in 0..n {
        out.extend_from_slice(unit);
    }
    Some(out)
}

struct Row {
    label: String,
    /// `Err` when ciborium cannot READ the embedded value at all. That is a
    /// finding about ciborium, not a reason to abandon the other 686 rows --
    /// which is exactly what happened before the fixture embedded each value
    /// as its own byte string: one tag-3 bignum below `i128::MIN` made the
    /// whole file undecodable and the Rust gate silently ran on nothing.
    value: Result<Value, String>,
    exp7049: Vec<u8>,
    exp8949: Vec<u8>,
}

fn parse_row(v: &Value) -> Result<Row, String> {
    let a = match v {
        Value::Array(a) if a.len() == 5 => a,
        _ => return Err("row is not a 5-element array".into()),
    };
    let label = match &a[0] {
        Value::Text(s) => s.clone(),
        _ => return Err("label is not text".into()),
    };
    let flags: Vec<&str> = match &a[4] {
        Value::Array(f) => f
            .iter()
            .filter_map(|x| match x {
                Value::Text(s) => Some(s.as_str()),
                _ => None,
            })
            .collect(),
        _ => vec![],
    };
    let rep = flags.contains(&"rep");
    let embedded = match &a[1] {
        Value::Bytes(b) => b,
        _ => return Err(format!("{}: value is not an embedded byte string", label)),
    };
    let value = match ciborium::de::from_reader::<Value, _>(&embedded[..]) {
        Ok(v) if rep => match expand_value(&v) {
            Some(x) => Ok(x),
            None => return Err(format!("{}: bad rep spec", label)),
        },
        Ok(v) => Ok(v),
        Err(e) => Err(format!("{}", e)),
    };
    let exp7049 = if rep {
        expand_enc(&a[2]).ok_or_else(|| format!("{}: bad rep-enc spec", label))?
    } else {
        match &a[2] {
            Value::Bytes(b) => b.clone(),
            _ => return Err(format!("{}: expected-7049 is not bytes", label)),
        }
    };
    let exp8949 = match &a[3] {
        // Empty means "identical to expected-7049". Unambiguous: no CBOR item
        // encodes to zero bytes.
        Value::Bytes(b) if b.is_empty() => exp7049.clone(),
        Value::Bytes(b) => b.clone(),
        _ => return Err(format!("{}: expected-8949 is not bytes", label)),
    };
    Ok(Row { label, value, exp7049, exp8949 })
}

// The two divergence classes that are understood. Everything else fails the
// build.
//
// CLASS_NAN is boring's deliberate choice, argued in Writer.writeShortestFloat
// and permitted by RFC 8949 4.2.2 ("if there is no intent to support NaN
// payloads or signaling NaNs, the protocol needs to pick a single
// representation, typically 0xf97e00"). It is a divergence from strict 4.1
// preferred serialization all the same, and cbor2 makes the same choice, so
// the Python gate is structurally blind to it and this is the only place it
// is visible.
const CLASS_NAN: &str =
    "NaN normalisation. boring (and cbor2) emit f97e00 for every NaN; ciborium keeps \
     the bits. RFC 8949 4.1 prefers a shorter float only when zero-padding the \
     significand reconstitutes the original, which for these it does not";
const CLASS_READ: &str =
    "ciborium cannot READ the value: a decoder limit (tag-3 bignum below i128::MIN), \
     not an encoding disagreement";
const CLASS_OTHER: &str = "UNCLASSIFIED";

fn main() -> ExitCode {
    let path = std::env::args()
        .nth(1)
        .unwrap_or_else(|| "interop/canonical_fixture.cbor".to_string());
    let bytes = match std::fs::read(&path) {
        Ok(b) => b,
        Err(e) => {
            eprintln!("cannot read {}: {}", path, e);
            return ExitCode::FAILURE;
        }
    };
    let top: Value = match ciborium::de::from_reader(&bytes[..]) {
        Ok(v) => v,
        Err(e) => {
            eprintln!("ciborium cannot decode the fixture: {}", e);
            return ExitCode::FAILURE;
        }
    };
    // The CanonicalValue pass is EXPECTED to panic on some inputs (see below),
    // and the default hook would print a backtrace per case. Findings are
    // reported by the summary at the end, not by the panic handler.
    std::panic::set_hook(Box::new(|_| {}));

    let rows = match &top {
        Value::Array(r) if !r.is_empty() => r,
        Value::Array(_) => {
            eprintln!("FAIL - the fixture is empty; the gate would pass vacuously");
            return ExitCode::FAILURE;
        }
        _ => {
            eprintln!("FAIL - the fixture is not an array of rows");
            return ExitCode::FAILURE;
        }
    };

    let mut checked = 0usize;
    let mut order_differs = 0usize;
    let mut failures: Vec<String> = Vec::new();
    let mut classes: BTreeMap<&'static str, Vec<String>> = BTreeMap::new();
    // Where ciborium's own CanonicalValue puts keys somewhere other than the
    // order of ciborium's own serialized bytes.
    let mut self_inconsistent: Vec<String> = Vec::new();
    // Where sorting with CanonicalValue does not merely give the wrong answer
    // but aborts: Rust's sort refuses a comparator that is not a total order.
    let mut cv_panics: Vec<String> = Vec::new();

    for rv in rows {
        let row = match parse_row(rv) {
            Ok(r) => r,
            Err(e) => {
                failures.push(e);
                continue;
            }
        };
        if row.exp7049 != row.exp8949 {
            order_differs += 1;
        }
        let value = match &row.value {
            Ok(v) => v,
            Err(e) => {
                classes
                    .entry(CLASS_READ)
                    .or_default()
                    .push(format!("    {:<46} {}", row.label, e));
                continue;
            }
        };
        checked += 1;

        let got = enc(&canonicalize(value, Order::SerializedBytes));
        if got != row.exp7049 {
            // Classify on the VALUE, not on the label: three NaN cases come out
            // of the binary16 bit-pattern sampler and are named `half-fc20`,
            // not `float-nan-*`.
            let class = if matches!(value, Value::Float(f) if f.is_nan()) {
                CLASS_NAN
            } else {
                CLASS_OTHER
            };
            classes.entry(class).or_default().push(format!(
                "    {:<24} boring {:<24} ciborium {}",
                row.label,
                hexs(&row.exp7049),
                hexs(&got)
            ));
        }

        // Second pass: ciborium's public comparator against ciborium's own
        // bytes. Reported so the caveat in this file's header stays a measured
        // number rather than an assertion someone has to take on trust.
        //
        // Wrapped in catch_unwind because `CanonicalValue`'s `Ord` is not
        // merely wrong, it is not a total order, and Rust's sort detects that
        // and panics: "user-provided comparison function does not correctly
        // implement a total order". On a wide enough map that is a runtime
        // abort in any program that sorts with it or puts it in a BTreeMap.
        let cv = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            enc(&canonicalize(value, Order::CanonicalValueOrd))
        }));
        match cv {
            Ok(b) if b != got => self_inconsistent.push(row.label.clone()),
            Ok(_) => {}
            Err(_) => cv_panics.push(row.label.clone()),
        }
    }

    println!(
        "corpus: {} cases ({} order-sensitive), independent encoder: ciborium",
        checked, order_differs
    );
    println!("check: boring :canonical-rfc7049 vs ciborium's octets, keys ordered length-first");

    let mut divergent = 0usize;
    for (class, lines) in &classes {
        divergent += lines.len();
        println!();
        println!("{} case(s) differ - {}:", lines.len(), class);
        for l in lines.iter().take(8) {
            println!("{}", l);
        }
        if lines.len() > 8 {
            println!("    ... and {} more", lines.len() - 8);
        }
    }

    if !self_inconsistent.is_empty() {
        println!();
        println!(
            "ciborium DISAGREES WITH ITSELF on {} of {} cases: sorting keys with \
             `CanonicalValue` puts them in a different order than sorting them by the \
             bytes `into_writer` produces for those same keys. Cause is \
             `Integer::canonical_cmp`, which sizes a negative integer from the \
             two's-complement width of the value rather than of the CBOR argument \
             -1-n, so it thinks -256 needs 3 bytes where ciborium writes `38ff`.",
            self_inconsistent.len(),
            checked
        );
        for l in self_inconsistent.iter().take(6) {
            println!("    {}", l);
        }
        if self_inconsistent.len() > 6 {
            println!("    ... and {} more", self_inconsistent.len() - 6);
        }
    }

    if !cv_panics.is_empty() {
        println!();
        println!(
            "ciborium's `CanonicalValue` is NOT A TOTAL ORDER: on {} of {} cases \
             Rust's sort aborted with \"user-provided comparison function does not \
             correctly implement a total order\". Sorting map keys with it, or \
             collecting them into a BTreeMap, is a runtime panic on those inputs.",
            cv_panics.len(),
            checked
        );
        for l in cv_panics.iter().take(6) {
            println!("    {}", l);
        }
        if cv_panics.len() > 6 {
            println!("    ... and {} more", cv_panics.len() - 6);
        }
    }

    if let Some(other) = classes.get(CLASS_OTHER) {
        for l in other {
            failures.push(l.trim().to_string());
        }
    }

    if !failures.is_empty() {
        println!();
        for f in &failures {
            println!("  FAIL {}", f);
        }
        println!(
            "FAILED - {} unexplained disagreements over {} cases",
            failures.len(),
            checked
        );
        return ExitCode::FAILURE;
    }

    println!();
    println!(
        "ok - {} of {} values encode to identical bytes in boring and ciborium \
         ({} documented divergences)",
        checked - divergent,
        checked,
        divergent
    );
    ExitCode::SUCCESS
}
