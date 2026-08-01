//! Read boring's CBOR from Rust.
//!
//! A companion to `interop/read_boring.py`, and here for the same reason:
//! an interop claim that nobody executes is a claim about intentions. This
//! reads the same committed fixture and checks the same values, so the two
//! languages agree or CI says so.
//!
//!     cargo run --manifest-path interop/rust/Cargo.toml -- interop/fixture.cbor
//!
//! Everything below is plain `ciborium::value::Value` walking. There is no
//! boring-specific library involved -- that is the point.

use std::collections::BTreeMap;
use std::process::ExitCode;

use ciborium::value::Value;

/// What boring's tags mean once decoded. A generic CBOR reader gives back
/// `Tag(n, payload)`; this is the whole translation table.
#[derive(Debug, Clone, PartialEq)]
enum Clj {
    /// Tag 39, "identifier". A keyword is the string with a leading `:`, a
    /// symbol the same string without one. Kept DISTINCT from `Str`: in
    /// Clojure `:a` and `"a"` are different map keys, and collapsing them
    /// silently merges entries.
    Keyword(String),
    Symbol(String),
    Str(String),
    Int(i128),
    Float(f64),
    Bool(bool),
    Null,
    Bytes(Vec<u8>),
    Array(Vec<Clj>),
    Map(Vec<(Clj, Clj)>),
    Set(Vec<Clj>),
    /// Tag 27, `[type-name, argument]`.
    Record(String, Box<Clj>),
    /// Anything this reader does not translate, kept rather than dropped so a
    /// mismatch is visible instead of silently absent.
    Other(u64, Box<Clj>),
}

fn convert(v: &Value) -> Clj {
    match v {
        Value::Integer(i) => Clj::Int((*i).into()),
        Value::Float(f) => Clj::Float(*f),
        Value::Text(s) => Clj::Str(s.clone()),
        Value::Bool(b) => Clj::Bool(*b),
        Value::Null => Clj::Null,
        Value::Bytes(b) => Clj::Bytes(b.clone()),
        Value::Array(a) => Clj::Array(a.iter().map(convert).collect()),
        Value::Map(m) => Clj::Map(m.iter().map(|(k, v)| (convert(k), convert(v))).collect()),
        Value::Tag(tag, inner) => convert_tag(*tag, inner),
        _ => Clj::Other(u64::MAX, Box::new(Clj::Null)),
    }
}

fn convert_tag(tag: u64, inner: &Value) -> Clj {
    match tag {
        // 25 and 256 are stringref: a compression detail, and ciborium does
        // NOT implement it. Handled below in `resolve_stringrefs`, before
        // this conversion runs, so nothing here has to know about it.
        39 => match inner {
            Value::Text(s) => {
                if let Some(rest) = s.strip_prefix(':') {
                    Clj::Keyword(rest.to_string())
                } else {
                    Clj::Symbol(s.clone())
                }
            }
            other => Clj::Other(39, Box::new(convert(other))),
        },
        27 => match inner {
            Value::Array(a) if a.len() == 2 => match &a[0] {
                Value::Text(name) => Clj::Record(name.clone(), Box::new(convert(&a[1]))),
                _ => Clj::Other(27, Box::new(convert(inner))),
            },
            _ => Clj::Other(27, Box::new(convert(inner))),
        },
        258 => match inner {
            Value::Array(a) => Clj::Set(a.iter().map(convert).collect()),
            _ => Clj::Other(258, Box::new(convert(inner))),
        },
        // RFC 8746 typed arrays. The payload is a plain little-endian memory
        // image, so a homogeneous numeric column is one bulk read rather than
        // one decode per element -- the fastest thing boring emits.
        //
        // 75 = uint64 LE is absent on purpose: a u64 above i64::MAX has no
        // lossless i128-free representation here and boring refuses to write
        // one, so seeing it means the input is not from boring.
        71 | 72 | 77 | 78 | 79 | 80 | 81 | 82 => match inner {
            Value::Bytes(b) => Clj::Array(typed_ints(tag, b)),
            _ => Clj::Other(tag, Box::new(convert(inner))),
        },
        85 | 86 => match inner {
            Value::Bytes(b) => Clj::Array(typed_floats(tag, b)),
            _ => Clj::Other(tag, Box::new(convert(inner))),
        },
        // 39649: boring's shaped array, [keys, [row-values...]]. Provisional,
        // off by default, and a pure zip -- it needs no state outside the tag,
        // which is exactly what makes it cheap to support here.
        39649 => match inner {
            Value::Array(a) if a.len() == 2 => {
                let keys: Vec<Clj> = match &a[0] {
                    Value::Array(k) => k.iter().map(convert).collect(),
                    _ => return Clj::Other(39649, Box::new(convert(inner))),
                };
                let rows: Vec<Clj> = match &a[1] {
                    Value::Array(rows) => rows
                        .iter()
                        .map(|row| match row {
                            Value::Array(vals) => Clj::Map(
                                keys.iter().cloned().zip(vals.iter().map(convert)).collect(),
                            ),
                            other => convert(other),
                        })
                        .collect(),
                    _ => return Clj::Other(39649, Box::new(convert(inner))),
                };
                Clj::Array(rows)
            }
            _ => Clj::Other(39649, Box::new(convert(inner))),
        },
        _ => Clj::Other(tag, Box::new(convert(inner))),
    }
}

fn typed_ints(tag: u64, b: &[u8]) -> Vec<Clj> {
    let (width, signed, be) = match tag {
        71 => (2, false, false),
        72 => (1, true, false),
        77 => (2, true, false),
        78 => (4, true, false),
        79 => (8, true, false),
        80 => (2, false, false),
        81 => (4, false, false),
        82 => (8, false, false),
        _ => unreachable!(),
    };
    b.chunks_exact(width)
        .map(|c| {
            let mut buf = [0u8; 8];
            if be {
                buf[8 - width..].copy_from_slice(c);
            } else {
                buf[..width].copy_from_slice(c);
            }
            let raw = if be {
                u64::from_be_bytes(buf)
            } else {
                u64::from_le_bytes(buf)
            };
            if signed {
                // Sign-extend from the element width. Skipping this reads -2 as
                // a large positive number, which `==` on the fixture catches
                // but a "looks like numbers" eyeball would not.
                let shift = 64 - (width * 8);
                Clj::Int(((raw << shift) as i64 >> shift) as i128)
            } else {
                Clj::Int(raw as i128)
            }
        })
        .collect()
}

fn typed_floats(tag: u64, b: &[u8]) -> Vec<Clj> {
    match tag {
        85 => b
            .chunks_exact(4)
            .map(|c| Clj::Float(f32::from_le_bytes(c.try_into().unwrap()) as f64))
            .collect(),
        86 => b
            .chunks_exact(8)
            .map(|c| Clj::Float(f64::from_le_bytes(c.try_into().unwrap())))
            .collect(),
        _ => unreachable!(),
    }
}

/// Stringref (tags 25 and 256, the schmorp extension) is how boring stops
/// paying for the same keyword 500 times. ciborium does not implement it, so
/// it is resolved here, over the raw `Value` tree, BEFORE any of the
/// translation above runs.
///
/// The rule: tag 256 opens a namespace; inside it, every text or byte string
/// long enough to be worth referencing is appended to a table in the order
/// encountered, and tag 25(n) means "the n-th entry of that table".
struct Namespace {
    table: Vec<Value>,
}

impl Namespace {
    /// A string is added to the table if referencing it is no LONGER than
    /// repeating it, so the comparison is `>=`, not `>`.
    ///
    /// This was `>` and it was wrong. A 3-byte string encodes as 4 bytes and a
    /// reference to index 0..23 costs 3, so it IS worth referencing -- boring
    /// registers it and so does cbor2 (verified: `cbor2.dumps(["abc","abc"],
    /// string_referencing=True)` emits `d819 00`). Skipping it here desynced
    /// the table, and since the tables must agree exactly, EVERY index after
    /// the first 3-byte string would have resolved to the wrong string.
    ///
    /// It passed anyway: the committed fixture happened to contain no 3-byte
    /// string in a referencing position. A reader used as an oracle needs the
    /// rule to be right for reasons the fixture does not happen to exercise --
    /// interop/fixture.cbor now carries that case.
    fn worth_referencing(len: usize, next_index: usize) -> bool {
        match next_index {
            0..=23 => len >= 3,
            24..=255 => len >= 4,
            256..=65535 => len >= 5,
            65536..=4294967295 => len >= 7,
            _ => len >= 11,
        }
    }

    fn observe(&mut self, v: &Value) {
        let len = match v {
            Value::Text(s) => s.len(),
            Value::Bytes(b) => b.len(),
            _ => return,
        };
        if Self::worth_referencing(len, self.table.len()) {
            self.table.push(v.clone());
        }
    }
}

fn resolve_stringrefs(v: &Value, ns: &mut Option<Namespace>) -> Result<Value, String> {
    match v {
        Value::Tag(256, inner) => {
            // A nested namespace shadows the outer one and is discarded on the
            // way out, so an index never leaks across a boundary.
            let mut fresh = Some(Namespace { table: Vec::new() });
            resolve_stringrefs(inner, &mut fresh)
        }
        Value::Tag(25, inner) => {
            let idx = match &**inner {
                Value::Integer(i) => i128::from(*i) as usize,
                _ => return Err("stringref index is not an integer".into()),
            };
            match ns {
                Some(n) => n
                    .table
                    .get(idx)
                    .cloned()
                    .ok_or_else(|| format!("stringref {idx} past end of table ({})", n.table.len())),
                None => Err(format!("stringref {idx} outside any tag-256 namespace")),
            }
        }
        Value::Text(_) | Value::Bytes(_) => {
            if let Some(n) = ns {
                n.observe(v);
            }
            Ok(v.clone())
        }
        Value::Array(a) => {
            let mut out = Vec::with_capacity(a.len());
            for x in a {
                out.push(resolve_stringrefs(x, ns)?);
            }
            Ok(Value::Array(out))
        }
        Value::Map(m) => {
            let mut out = Vec::with_capacity(m.len());
            for (k, val) in m {
                let k = resolve_stringrefs(k, ns)?;
                let val = resolve_stringrefs(val, ns)?;
                out.push((k, val));
            }
            Ok(Value::Map(out))
        }
        Value::Tag(t, inner) => Ok(Value::Tag(*t, Box::new(resolve_stringrefs(inner, ns)?))),
        other => Ok(other.clone()),
    }
}

// ------------------------------------------------------------------ checking

fn as_map(v: &Clj) -> BTreeMap<String, Clj> {
    let mut out = BTreeMap::new();
    if let Clj::Map(entries) = v {
        for (k, val) in entries {
            if let Clj::Str(s) = k {
                out.insert(s.clone(), val.clone());
            }
        }
    }
    out
}

fn main() -> ExitCode {
    let path = match std::env::args().nth(1) {
        Some(p) => p,
        None => {
            eprintln!("usage: read-boring <fixture.cbor>");
            return ExitCode::from(2);
        }
    };
    let bytes = match std::fs::read(&path) {
        Ok(b) => b,
        Err(e) => {
            eprintln!("cannot read {path}: {e}");
            return ExitCode::from(2);
        }
    };

    let raw: Value = match ciborium::from_reader(&bytes[..]) {
        Ok(v) => v,
        Err(e) => {
            eprintln!("not valid CBOR: {e}");
            return ExitCode::from(1);
        }
    };
    let resolved = match resolve_stringrefs(&raw, &mut None) {
        Ok(v) => v,
        Err(e) => {
            eprintln!("stringref resolution failed: {e}");
            return ExitCode::from(1);
        }
    };
    let top = as_map(&convert(&resolved));

    let mut failures: Vec<String> = Vec::new();
    // A macro rather than a closure: a closure capturing `failures` mutably
    // would hold that borrow for the rest of the function, and the set check
    // below needs to push too.
    macro_rules! check {
        ($key:expr, $want:expr $(,)?) => {
            match top.get($key) {
                Some(got) if *got == $want => {}
                Some(got) => failures.push(format!(
                    "{}: expected {:?}, got {:?}", $key, $want, got)),
                None => failures.push(format!("{}: missing from fixture", $key)),
            }
        };
    }

    check!("string", Clj::Str("hello".into()));
    check!("string-utf8", Clj::Str("héllo wörld 💩".into()));
    check!("int", Clj::Int(42));
    check!("int-negative", Clj::Int(-7));
    check!("float", Clj::Float(1.5));
    check!("bool-true", Clj::Bool(true));
    check!("bool-false", Clj::Bool(false));
    check!("null", Clj::Null);
    check!("vector", Clj::Array(vec![Clj::Int(1), Clj::Int(2), Clj::Int(3)]));
    check!("bytes", Clj::Bytes(vec![0x01, 0x02, 0xfd]));

    // Tag 39 is where Clojure shows through, and the reason it matters is that
    // a keyword must NOT arrive as the bare string.
    check!("keyword", Clj::Keyword("user/name".into()));
    check!("keyword-bare", Clj::Keyword("simple".into()));
    check!("symbol", Clj::Symbol("my.ns/sym".into()));
    check!(
        "map",
        Clj::Map(vec![
            (Clj::Keyword("a".into()), Clj::Int(1)),
            (Clj::Keyword("b".into()), Clj::Str("two".into())),
        ]),
    );
    check!(
        "record",
        Clj::Record(
            "gen_interop_fixture.Point".into(),
            Box::new(Clj::Map(vec![
                (Clj::Keyword("x".into()), Clj::Int(3)),
                (Clj::Keyword("y".into()), Clj::Int(4)),
            ])),
        ),
    );

    // RFC 8746 typed arrays: registered, standard, and the thing generic
    // readers most often leave as raw bytes.
    check!(
        "long-array",
        Clj::Array(vec![Clj::Int(1), Clj::Int(-2), Clj::Int(3)]),
    );
    check!(
        "double-array",
        Clj::Array(vec![Clj::Float(1.5), Clj::Float(-2.5)]),
    );

    // Tag 258 wraps an ARRAY, and a Clojure hash-set has no meaningful
    // iteration order -- this fixture emits 1, 3, 2. A foreign reader that
    // compares set contents positionally will pass on some values and fail on
    // others, which is worse than failing consistently. Compared as an
    // unordered collection, which is what the tag means.
    match top.get("set") {
        Some(Clj::Set(items)) => {
            let mut got: Vec<i128> = items
                .iter()
                .filter_map(|x| match x {
                    Clj::Int(i) => Some(*i),
                    _ => None,
                })
                .collect();
            got.sort_unstable();
            if got != vec![1, 2, 3] {
                failures.push(format!("set: expected {{1,2,3}}, got {got:?}"));
            }
        }
        other => failures.push(format!("set: expected a tag-258 set, got {other:?}")),
    }

    // The threshold boundary. With `>` instead of `>=` above, "wxyz" gets
    // index 0 here instead of 1 and this reads ["abc","abc","wxyz","abc"].
    check!(
        "sr-threshold",
        Clj::Array(vec![
            Clj::Str("abc".into()),
            Clj::Str("abc".into()),
            Clj::Str("wxyz".into()),
            Clj::Str("wxyz".into()),
        ]),
    );

    check!(
        "shaped",
        Clj::Array(vec![
            Clj::Map(vec![
                (Clj::Keyword("e".into()), Clj::Int(1)),
                (Clj::Keyword("a".into()), Clj::Keyword("x".into())),
            ]),
            Clj::Map(vec![
                (Clj::Keyword("e".into()), Clj::Int(2)),
                (Clj::Keyword("a".into()), Clj::Keyword("y".into())),
            ]),
        ]),
    );

    // A keyword must not equal the plain string, or a consumer using them as
    // map keys silently conflates :a with "a".
    if top.get("keyword") == Some(&Clj::Str("user/name".into())) {
        failures.push("Keyword compares equal to the un-prefixed string".into());
    }

    if failures.is_empty() {
        println!("ok — 21 values read from boring's CBOR by Rust/ciborium");
        ExitCode::SUCCESS
    } else {
        println!("FAIL — {} problem(s):", failures.len());
        for f in &failures {
            println!("  - {f}");
        }
        ExitCode::from(1)
    }
}
