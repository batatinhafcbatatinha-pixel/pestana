//! Minimal JSON writer (no dependencies, keeps the library small).

pub struct Json {
    buf: String,
    need_comma: Vec<bool>,
    after_key: bool,
}

impl Json {
    pub fn object() -> Json {
        Json { buf: String::from("{"), need_comma: vec![false], after_key: false }
    }

    fn sep(&mut self) {
        if self.after_key {
            self.after_key = false;
            return;
        }
        if let Some(n) = self.need_comma.last_mut() {
            if *n {
                self.buf.push(',');
            }
            *n = true;
        }
    }

    fn raw_str(&mut self, s: &str) {
        self.buf.push('"');
        for c in s.chars() {
            match c {
                '"' => self.buf.push_str("\\\""),
                '\\' => self.buf.push_str("\\\\"),
                '\n' => self.buf.push_str("\\n"),
                '\r' => self.buf.push_str("\\r"),
                '\t' => self.buf.push_str("\\t"),
                c if (c as u32) < 0x20 => self.buf.push_str(&format!("\\u{:04x}", c as u32)),
                c => self.buf.push(c),
            }
        }
        self.buf.push('"');
    }

    pub fn key(&mut self, k: &str) -> &mut Self {
        self.sep();
        self.raw_str(k);
        self.buf.push(':');
        self.after_key = true;
        self
    }

    pub fn string(&mut self, s: &str) -> &mut Self {
        self.sep();
        self.raw_str(s);
        self
    }

    pub fn int(&mut self, v: i64) -> &mut Self {
        self.sep();
        self.buf.push_str(&v.to_string());
        self
    }

    pub fn bool(&mut self, v: bool) -> &mut Self {
        self.sep();
        self.buf.push_str(if v { "true" } else { "false" });
        self
    }

    pub fn begin_array(&mut self) -> &mut Self {
        self.sep();
        self.buf.push('[');
        self.need_comma.push(false);
        self
    }

    pub fn end_array(&mut self) -> &mut Self {
        self.need_comma.pop();
        self.buf.push(']');
        self
    }

    pub fn begin_object(&mut self) -> &mut Self {
        self.sep();
        self.buf.push('{');
        self.need_comma.push(false);
        self
    }

    pub fn end_object(&mut self) -> &mut Self {
        self.need_comma.pop();
        self.buf.push('}');
        self
    }

    pub fn finish(mut self) -> String {
        self.buf.push('}');
        self.buf
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn writes() {
        let mut j = super::Json::object();
        j.key("a").int(1);
        j.key("b").begin_array();
        j.string("x\"y").bool(true);
        j.begin_object();
        j.key("c").string("é");
        j.end_object();
        j.end_array();
        assert_eq!(j.finish(), r#"{"a":1,"b":["x\"y",true,{"c":"é"}]}"#);
    }
}
