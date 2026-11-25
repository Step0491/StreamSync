"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const {
  StreamKind,
  stripRangeQueryParams,
  parseHlsManifest,
  parseDashManifest,
  inspectContainerBytes,
} = require("../src/manifestParser");

test("stripRangeQueryParams removes byte-range params while keeping auth tokens", () => {
  const raw = "https://cdn.example.com/v.mp4?token=abc&range=0-65535&bytestart=0&exp=999";
  assert.equal(stripRangeQueryParams(raw), "https://cdn.example.com/v.mp4?token=abc&exp=999");
});

test("parseHlsManifest separates Master Combined, Video-Only variants, and Audio-Only renditions", () => {
  const m3u8 = [
    "#EXTM3U",
    '#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a1",LANGUAGE="it",NAME="Italiano",URI="audio/it.m3u8"',
    '#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a1",LANGUAGE="en",NAME="English",URI="audio/en.m3u8"',
    '#EXT-X-STREAM-INF:BANDWIDTH=4500000,RESOLUTION=1920x1080,FRAME-RATE=60,CODECS="avc1.64002a,mp4a.40.2",AUDIO="a1"',
    "video/1080p.m3u8",
  ].join("\n");

  const res = parseHlsManifest("https://cdn.example.com/film/master.m3u8", m3u8, { Referer: "https://example.com" });
  assert.ok(res);
  assert.equal(res.isDrmProtected, false);

  const combined = res.streams.filter((s) => s.kind === StreamKind.COMBINED);
  const videoOnly = res.streams.filter((s) => s.kind === StreamKind.VIDEO_ONLY);
  const audioOnly = res.streams.filter((s) => s.kind === StreamKind.AUDIO_ONLY);

  assert.equal(combined.length, 1);
  assert.equal(combined[0].isMasterPlaylist, true);
  assert.equal(videoOnly.length, 1);
  assert.equal(videoOnly[0].height, 1080);
  assert.equal(audioOnly.length, 2);
  assert.equal(res.childOverrides.get("https://cdn.example.com/film/video/1080p.m3u8").kind, StreamKind.VIDEO_ONLY);
});

test("parseDashManifest extracts BaseURL tracks and flags Widevine DRM", () => {
  const mpd = `<?xml version="1.0"?>
  <MPD type="static">
    <Period>
      <AdaptationSet contentType="video" mimeType="video/mp4">
        <Representation width="1920" height="1080" bandwidth="5000000" codecs="avc1.640028">
          <BaseURL>v1080.mp4</BaseURL>
        </Representation>
      </AdaptationSet>
      <AdaptationSet contentType="audio" mimeType="audio/mp4" lang="it">
        <Representation bandwidth="128000" codecs="mp4a.40.2">
          <BaseURL>a_it.m4a</BaseURL>
        </Representation>
      </AdaptationSet>
    </Period>
  </MPD>`;

  const clean = parseDashManifest("https://cdn.example.com/dash/stream.mpd", mpd);
  assert.ok(clean);
  assert.equal(clean.isDrmProtected, false);
  assert.equal(clean.streams[0].kind, StreamKind.COMBINED);
  assert.ok(clean.streams.some((s) => s.kind === StreamKind.VIDEO_ONLY && s.url.endsWith("/v1080.mp4")));
  assert.ok(clean.streams.some((s) => s.kind === StreamKind.AUDIO_ONLY && s.language === "it"));

  const drm = parseDashManifest(
    "https://cdn.example.com/dash/enc.mpd",
    mpd.replace("<Period>", '<Period><ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"/>')
  );
  assert.equal(drm.isDrmProtected, true);
});

test("inspectContainerBytes detects ISO-BMFF vide+soun vs vide-only vs soun-only", () => {
  const makeHdlr = (type, codec) =>
    Buffer.concat([
      Buffer.from([0, 0, 0, 32]),
      Buffer.from("hdlr"),
      Buffer.alloc(8),
      Buffer.from(type),
      Buffer.alloc(12),
      Buffer.from(codec),
    ]);

  const muxed = Buffer.concat([Buffer.from("0000ftypisommoov"), makeHdlr("vide", "avc1"), makeHdlr("soun", "mp4a")]);
  const videoOnly = Buffer.concat([Buffer.from("0000ftypisommoov"), makeHdlr("vide", "avc1")]);
  const audioOnly = Buffer.concat([Buffer.from("0000ftypisommoov"), makeHdlr("soun", "mp4a")]);

  assert.equal(inspectContainerBytes(muxed, "video/mp4", "https://x/a.mp4").kind, StreamKind.COMBINED);
  assert.equal(inspectContainerBytes(videoOnly, "video/mp4", "https://x/v.mp4").kind, StreamKind.VIDEO_ONLY);
  assert.equal(inspectContainerBytes(audioOnly, "audio/mp4", "https://x/a.m4a").kind, StreamKind.AUDIO_ONLY);
});
