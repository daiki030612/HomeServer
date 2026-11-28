const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");

function page(file) {
    let submit, sent = 0;
    const alerts = [], reads = [];
    const elements = {};
    for (const name of ["upload-form", "file", "file-name", "upload-button", "progress-area", "progress-bar", "progress-text", "progress-size"]) {
        elements[name] = { style: {}, addEventListener(name, callback) {
            if (name === "submit") submit = callback;
        }};
    }
    elements.file.files = [file];
    elements["upload-form"].action = "http://localhost/videos/upload";
    class XHR {
        constructor() { this.upload = { addEventListener() {} }; }
        open() {} setRequestHeader() {} addEventListener() {}
        send() { sent++; }
    }
    const context = { Uint8Array, URLSearchParams, console, setTimeout,
        window: { location: { search: "" } }, alert: x => alerts.push(x),
        FormData: class { append() {} }, XMLHttpRequest: XHR,
        document: {
            addEventListener(name, callback) { callback(); },
            getElementById(name) { return elements[name] ?? null; },
            querySelector() { return { content: "csrf" }; }
        }
    };
    vm.runInNewContext(fs.readFileSync("src/main/resources/static/js/video/upload.js", "utf8"), context);
    return { run: () => submit({ preventDefault() {} }), alerts, sent: () => sent };
}
function file(name, size, bytes) {
    return { name, size, slice(start, end) {
        assert.equal(start, 0); assert.equal(end, 16);
        return { async arrayBuffer() { return Uint8Array.from(bytes).buffer; } };
    }};
}
test("valid MKV uses only a bounded header read before sending", async () => {
    const p = page(file("sample.MKV", 4.1 * 1024 ** 3, [0x1a,0x45,0xdf,0xa3]));
    await p.run(); assert.equal(p.sent(), 1); assert.equal(p.alerts.length, 0);
});
test("mismatched container is rejected before upload", async () => {
    const p = page(file("fake.mkv", 100, [0,0,0,0,102,116,121,112]));
    await p.run(); assert.equal(p.sent(), 0); assert.equal(p.alerts.length, 1);
});
test("more than 10 GiB is rejected before any file read", async () => {
    const p = page({ name: "huge.mp4", size: 10 * 1024 ** 3 + 1,
        slice() { throw new Error("must not read"); } });
    await p.run(); assert.equal(p.sent(), 0); assert.equal(p.alerts.length, 1);
});
