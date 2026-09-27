// Builds data/world.js: country shapes (Natural Earth 1:50m via world-atlas) plus the
// country list GlobeTint counts against, following the UN view of borders.
//
//   cd tools && npm install && node build-data.mjs
//
// Border rules (see README):
//   * Kosovo is part of Serbia, N. Cyprus of Cyprus, Somaliland of Somalia.
//   * Crimea is part of Ukraine.
//   * 193 UN members + Vatican + Palestine are counted (195). Everything else on the map
//     (Taiwan, Hong Kong, Greenland, ...) is a territory: it can be coloured but is not counted.

import { readFileSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import * as topojson from "topojson-client";
import { presimplify, simplify, quantile } from "topojson-simplify";
import { geoContains } from "d3-geo";
import countries from "i18n-iso-countries";
import currencyOf from "country-to-currency";

const require = createRequire(import.meta.url);
const topo = JSON.parse(readFileSync(require.resolve("world-atlas/countries-50m.json"), "utf8"));

// ---- The 195 counted countries, by continent (as agreed: Russia, Turkey, Cyprus and the
// Caucasus in Europe; Israel and Kazakhstan in Asia; one America). ----
const CONTINENTS = {
  EU: "AL AD AT BY BE BA BG HR CZ DK EE FI FR DE GR HU IS IE IT LV LI LT LU MT MD MC ME NL MK NO PL PT RO RU SM RS SK SI ES SE CH UA GB VA TR CY AM GE AZ",
  AS: "AF BH BD BT BN KH CN IN ID IR IQ IL JP JO KZ KW KG LA LB MY MV MN MM NP KP OM PK PS PH QA SA SG KR LK SY TJ TH TL TM AE UZ VN YE",
  AF: "DZ AO BJ BW BF BI CV CM CF TD KM CG CD CI DJ EG GQ ER SZ ET GA GM GH GN GW KE LS LR LY MG MW ML MR MU MA MZ NA NE NG RW ST SN SC SL SO ZA SS SD TZ TG TN UG ZM ZW",
  AM: "AG AR BS BB BZ BO BR CA CL CO CR CU DM DO EC SV GD GT GY HT HN JM MX NI PA PY PE KN LC VC SR TT US UY VE",
  OC: "AU FJ KI MH FM NR NZ PW PG WS SB TO TV VU",
};
// Territories shown on the map (coloured, never counted).
const TERRITORIES = {
  EU: "AX FO JE GG IM",
  AS: "TW HK MO IO CX",
  AF: "EH SH TF",
  AM: "GL PR VI VG AI KY BM TC MS AW CW SX MF BL PM FK GS",
  OC: "GU MP AS PF NC WF CK NU PN NF HM",
};
// Map features without an ISO numeric code.
const BY_NAME = { Kosovo: "RS", "N. Cyprus": "CY", Somaliland: "SO", "Indian Ocean Ter.": "CX" };
// Not part of any country here; drawn as plain land.
const SKIP = new Set(["Antarctica", "Siachen Glacier"]);
// Counted countries too small for the 1:50m map: shown as a dot.
const POINTS = { TV: [179.19, -8.52] };

const meta = {};
for (const [ct, list] of Object.entries(CONTINENTS)) for (const c of list.split(" ")) meta[c] = { ct, counted: true };
for (const [ct, list] of Object.entries(TERRITORIES)) for (const c of list.split(" ")) meta[c] = { ct, counted: false };
const counted = Object.values(meta).filter((m) => m.counted).length;
if (counted !== 195) throw new Error(`Expected 195 counted countries, got ${counted}`);

// ---- Group map geometries by ISO alpha-2 ----
const groups = new Map();
const neutral = [];
for (const g of topo.objects.countries.geometries) {
  const name = g.properties.name;
  if (name === "Antarctica") continue;
  if (SKIP.has(name)) { neutral.push(g); continue; }
  const iso = BY_NAME[name] || countries.numericToAlpha2(g.id);
  if (!iso || !meta[iso]) throw new Error(`No country for map feature ${g.id} ${name}`);
  (groups.get(iso) || groups.set(iso, []).get(iso)).push(g);
}

// Crimea: move Russia's polygon that contains Simferopol to Ukraine.
{
  const ru = groups.get("RU")[0];
  const i = ru.arcs.findIndex((poly) =>
    geoContains(topojson.feature(topo, { type: "Polygon", arcs: poly }), [34.1, 44.95]));
  if (i < 0) throw new Error("Crimea polygon not found");
  const crimea = { type: "Polygon", arcs: ru.arcs[i] };
  ru.arcs = ru.arcs.filter((_, j) => j !== i);
  groups.get("UA").push(crimea);
}

const missing = Object.keys(meta).filter((c) => !groups.has(c) && !POINTS[c]);
if (missing.length) throw new Error(`Countries missing from the map: ${missing.join(" ")}`);

// One geometry per country; merging removes the inner borders (e.g. Serbia–Kosovo).
const geometries = [...groups].map(([id, gs]) => {
  const geom = gs.length > 1 ? topojson.mergeArcs(topo, gs) : { type: gs[0].type, arcs: gs[0].arcs };
  return { ...geom, id };
});
for (const g of neutral) geometries.push({ type: g.type, arcs: g.arcs });
topo.objects = { countries: { type: "GeometryCollection", geometries } };

// ---- Simplify (keeps shared borders consistent) and drop unused arcs ----
let out = presimplify(topo);
out = simplify(out, quantile(out, 0.45));
prune(out);
out = topojson.quantize(out, 1e5); // integer, delta-encoded coordinates

// Short English names (also the fallback when the phone lacks translated names).
const EN = { TW: "Taiwan", PS: "Palestine", VA: "Vatican City", US: "United States", GB: "United Kingdom",
  RU: "Russia", IR: "Iran", SY: "Syria", KR: "South Korea", KP: "North Korea", LA: "Laos", VN: "Vietnam",
  BO: "Bolivia", VE: "Venezuela", TZ: "Tanzania", MD: "Moldova", BN: "Brunei", FM: "Micronesia",
  CD: "DR Congo", CG: "Congo", CI: "Côte d'Ivoire", CZ: "Czechia", MK: "North Macedonia",
  FK: "Falkland Islands", HM: "Heard and McDonald Islands", GS: "South Georgia", TF: "French Southern Lands",
  MF: "Saint Martin", BL: "Saint Barthélemy", SX: "Sint Maarten", VI: "U.S. Virgin Islands", VG: "British Virgin Islands",
  IO: "British Indian Ocean Territory", CX: "Christmas Island", EH: "Western Sahara", HK: "Hong Kong", MO: "Macao",
  SH: "Saint Helena", PN: "Pitcairn Islands", AX: "Åland", TR: "Türkiye" };
const metaOut = {};
for (const [c, m] of Object.entries(meta)) {
  metaOut[c] = [m.ct, m.counted ? 1 : 0, currencyOf[c] || "", EN[c] || countries.getName(c, "en") || c];
}

const js = "// Generated by tools/build-data.mjs from Natural Earth (public domain). Do not edit.\n" +
  "window.GT_DATA=" + JSON.stringify({ topo: out, meta: metaOut, points: POINTS }) + ";\n";
writeFileSync(new URL("../data/world.js", import.meta.url), js);
console.log(`data/world.js: ${(js.length / 1024).toFixed(0)} KB, ${geometries.length} shapes, ${Object.keys(metaOut).length} countries/territories`);

function prune(t) {
  const used = new Map(), arcs = [];
  const remap = (i) => {
    const k = i < 0 ? ~i : i;
    if (!used.has(k)) { used.set(k, arcs.length); arcs.push(t.arcs[k]); }
    return i < 0 ? ~used.get(k) : used.get(k);
  };
  const walk = (a) => (typeof a === "number" ? remap(a) : a.map(walk));
  for (const g of t.objects.countries.geometries) g.arcs = walk(g.arcs);
  t.arcs = arcs;
}
