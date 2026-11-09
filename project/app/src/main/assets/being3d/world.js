/* Atlas Being 3D home: a cozy cutaway room the Being lives in. Classic script (no modules) so it loads straight from the app. */
(function () {
  'use strict';
  var T = THREE;
  var W = innerWidth, H = innerHeight;
  var renderer = new T.WebGLRenderer({ antialias: true, alpha: true });
  renderer.setPixelRatio(Math.min(devicePixelRatio || 1, 2));
  renderer.setSize(W, H);
  renderer.shadowMap.enabled = true; renderer.shadowMap.type = T.PCFSoftShadowMap;
  renderer.toneMapping = T.ACESFilmicToneMapping; renderer.toneMappingExposure = 1.15;
  document.getElementById('gl').appendChild(renderer.domElement);
  var scene = new T.Scene();
  var fw = 14.0, asp = W / H;
  var cam = new T.OrthographicCamera(-fw / 2, fw / 2, fw / 2 / asp, -fw / 2 / asp, 0.1, 120);
  var yaw = Math.PI / 4, pitch = 1.0, zoom = 1.0;
  function placeCam() {
    var d = 24, cx = Math.cos(pitch) * d;
    cam.position.set(Math.sin(yaw) * cx, Math.sin(pitch) * d, Math.cos(yaw) * cx);
    cam.zoom = zoom; cam.lookAt(0, 0.7, 0.3); cam.updateProjectionMatrix();
  }
  placeCam();
  function post(o) { try { Android.onEvent(JSON.stringify(o)); } catch (e) { } }

  // ── lights ──
  var hemi = new T.HemisphereLight(0xfff3e6, 0x7b6a8c, 0.95); scene.add(hemi);
  var sun = new T.DirectionalLight(0xffc27a, 2.4); sun.position.set(-3, 9, 10); sun.castShadow = true;
  sun.shadow.mapSize.set(1024, 1024);
  sun.shadow.camera.left = -9; sun.shadow.camera.right = 9; sun.shadow.camera.top = 9; sun.shadow.camera.bottom = -9; sun.shadow.camera.near = 1; sun.shadow.camera.far = 40;
  sun.shadow.bias = -0.0005; scene.add(sun);
  var lamp = new T.PointLight(0xffb86b, 14, 9, 1.6); lamp.position.set(3.3, 2.8, -2.6); scene.add(lamp);

  // ── helpers ──
  function M(c, r, m, e) { return new T.MeshStandardMaterial({ color: c, roughness: r == null ? .7 : r, metalness: m || 0, emissive: e || 0, emissiveIntensity: e ? 1 : 0 }); }
  function rb(w, h, d, x, y, z, mat, rad, parent) {
    var r = Math.min(rad == null ? .08 : rad, w / 2.2, h / 2.2, d / 2.2);
    var m = new T.Mesh(new RoundedBoxGeometry(w, h, d, 3, r), mat); m.position.set(x, y, z); m.castShadow = true; m.receiveShadow = true; (parent || scene).add(m); return m;
  }
  function cyl(rt, rb2, h, x, y, z, mat, parent) { var m = new T.Mesh(new T.CylinderGeometry(rt, rb2, h, 24), mat); m.position.set(x, y, z); m.castShadow = true; m.receiveShadow = true; (parent || scene).add(m); return m; }
  function sph(r, x, y, z, mat, parent) { var m = new T.Mesh(new T.SphereGeometry(r, 24, 16), mat); m.position.set(x, y, z); m.castShadow = true; m.receiveShadow = true; (parent || scene).add(m); return m; }
  function canv(w, h, fn) { var c = document.createElement('canvas'); c.width = w; c.height = h; fn(c.getContext('2d'), w, h); var t = new T.CanvasTexture(c); t.colorSpace = T.SRGBColorSpace; t.anisotropy = 4; return t; }
  function grp(x, y, z, parent) { var g = new T.Group(); g.position.set(x, y, z); (parent || scene).add(g); return g; }

  // ── room ──
  var floorTex = canv(512, 512, function (g, w, h) {
    g.fillStyle = '#c99763'; g.fillRect(0, 0, w, h);
    for (var i = 0; i < 8; i++) { g.fillStyle = 'hsl(30,' + (40 + i % 3 * 6) + '%,' + (52 + (i % 4) * 3) + '%)'; g.fillRect(0, i * 64, w, 62); g.fillStyle = 'rgba(60,30,10,.35)'; g.fillRect(0, i * 64 + 62, w, 2); }
  });
  floorTex.wrapS = floorTex.wrapT = T.RepeatWrapping; floorTex.repeat.set(2, 2);
  rb(9, .35, 9, 0, -.175, 0, new T.MeshStandardMaterial({ map: floorTex, roughness: .55 }), .06);
  var wallMat = M(0xf3e6d3, .9);
  rb(9, 4.2, .3, 0, 2.1, -4.35, wallMat, .05); rb(.3, 4.2, 9, -4.35, 2.1, 0, wallMat, .05);
  rb(9, .45, .34, 0, .22, -4.33, M(0xb98a5a), .04); rb(.34, .45, 9, -4.33, .22, 0, M(0xb98a5a), .04);
  rb(9.4, .18, .5, 0, 4.28, -4.35, M(0xffffff), .04); rb(.5, .18, 9.4, -4.35, 4.28, 0, M(0xffffff), .04);
  for (var i = 0; i < 4; i++) rb(1.9, .9, .06, -3.2 + i * 2.2, 1.1, -4.17, M(0xe8d3b8, .9), .03);

  var winCanvas = document.createElement('canvas'); winCanvas.width = 256; winCanvas.height = 256;
  var winTex = new T.CanvasTexture(winCanvas); winTex.colorSpace = T.SRGBColorSpace;
  var win = new T.Mesh(new T.PlaneGeometry(2.4, 2.1), new T.MeshBasicMaterial({ map: winTex })); win.position.set(1.0, 2.5, -4.19); scene.add(win);
  function drawWindow(hour) {
    var g = winCanvas.getContext('2d'), h = 256, top, mid, bot, night = hour >= 20 || hour < 5, dusk = (hour >= 17 && hour < 20), dawn = (hour >= 5 && hour < 7);
    if (night) { top = '#0b1030'; mid = '#1b2250'; bot = '#2d2f6b'; } else if (dusk) { top = '#5a79c9'; mid = '#ffb27a'; bot = '#ff7a6b'; } else if (dawn) { top = '#6f9bd8'; mid = '#ffd3a0'; bot = '#ffb48a'; } else { top = '#58a9ff'; mid = '#9fd4ff'; bot = '#d6eeff'; }
    var gr = g.createLinearGradient(0, 0, 0, h); gr.addColorStop(0, top); gr.addColorStop(.6, mid); gr.addColorStop(1, bot); g.fillStyle = gr; g.fillRect(0, 0, 256, h);
    if (night) { g.fillStyle = '#fff'; for (var i = 0; i < 40; i++) { g.globalAlpha = .4 + Math.random() * .6; g.fillRect(Math.random() * 256, Math.random() * 170, 2, 2); } g.globalAlpha = 1; g.fillStyle = '#fff6d6'; g.beginPath(); g.arc(190, 60, 20, 0, 7); g.fill(); g.fillStyle = '#1b2250'; g.beginPath(); g.arc(198, 56, 18, 0, 7); g.fill(); }
    else { g.fillStyle = dusk || dawn ? '#ffe3b0' : '#fff7c2'; g.beginPath(); g.arc(dusk ? 190 : 70, dusk ? 172 : 60, 22, 0, 7); g.fill(); g.fillStyle = 'rgba(255,255,255,.75)'; g.beginPath(); g.ellipse(60, 70, 36, 11, 0, 0, 7); g.ellipse(170, 40, 30, 9, 0, 0, 7); g.fill(); }
    winTex.needsUpdate = true;
  }
  var white = M(0xffffff);
  rb(2.7, .14, .2, 1.0, 1.4, -4.15, white, .04); rb(2.7, .14, .2, 1.0, 3.6, -4.15, white, .04); rb(.14, 2.36, .2, -.33, 2.5, -4.15, white, .04); rb(.14, 2.36, .2, 2.33, 2.5, -4.15, white, .04); rb(.08, 2.1, .1, 1.0, 2.5, -4.12, white, .02);
  rb(.5, 2.7, .12, -.45, 2.5, -4.05, M(0xe9967a, .95), .05); rb(.5, 2.7, .12, 2.45, 2.5, -4.05, M(0xe9967a, .95), .05);

  var rugTex = canv(256, 256, function (g) {
    g.fillStyle = '#2b8f96'; g.fillRect(0, 0, 256, 256); g.strokeStyle = '#ffd27a'; g.lineWidth = 6; g.beginPath(); g.arc(128, 128, 110, 0, 7); g.stroke();
    g.strokeStyle = '#ff7ab6'; g.lineWidth = 4; g.beginPath(); g.arc(128, 128, 84, 0, 7); g.stroke(); g.fillStyle = '#ffd27a';
    for (var i = 0; i < 8; i++) { var a = i / 8 * 6.283; g.beginPath(); g.arc(128 + Math.cos(a) * 50, 128 + Math.sin(a) * 50, 7, 0, 7); g.fill(); }
  });
  var rug = new T.Mesh(new T.CylinderGeometry(2.2, 2.2, .06, 48), new T.MeshStandardMaterial({ map: rugTex, roughness: 1 })); rug.position.set(.4, .03, .6); rug.receiveShadow = true; scene.add(rug);

  // sofa (back wall, right)
  var sofa = grp(1.6, 0, -3.1);
  rb(3.3, .7, 1.4, 0, .5, 0, M(0xe86a5c, .8), .2, sofa); rb(3.3, 1.0, .5, 0, 1.0, -.45, M(0xd9584b, .8), .2, sofa);
  rb(.45, .95, 1.4, -1.7, .78, 0, M(0xd9584b, .8), .18, sofa); rb(.45, .95, 1.4, 1.7, .78, 0, M(0xd9584b, .8), .18, sofa);
  rb(1.4, .3, 1.1, -.75, .98, .05, M(0xf08a7c, .85), .14, sofa); rb(1.4, .3, 1.1, .75, .98, .05, M(0xf08a7c, .85), .14, sofa);
  // bed (back wall, left)
  var bed = grp(-2.7, 0, -3.0);
  rb(2.2, .45, 2.3, 0, .3, 0, M(0x8a5a36, .6), .08, bed); rb(2.0, .3, 2.1, 0, .66, 0, M(0xf2f2f7, .9), .14, bed);
  rb(2.0, .22, 1.2, 0, .86, .5, M(0x4b5bd8, .85), .1, bed); rb(.9, .22, .5, -.45, .9, -.8, M(0xffffff, .9), .1, bed); rb(.9, .22, .5, .45, .9, -.8, M(0xffd27a, .9), .1, bed);
  rb(2.2, 1.3, .15, 0, .95, -1.1, M(0x6a4228, .6), .06, bed);
  // coffee table + mug + book
  rb(1.5, .12, .9, .85, .6, -.8, M(0xb67f4a, .5), .05);
  [[-.6, -.3], [.6, -.3], [-.6, .3], [.6, .3]].forEach(function (p) { cyl(.06, .05, .6, .85 + p[0], .3, -.8 + p[1], M(0x6a4a2a)); });
  rb(.5, .07, .38, .95, .69, -.8, M(0x4b5bd8), .02); cyl(.12, .1, .2, .6, .74, -.7, M(0xffffff));
  // bookshelf (left wall)
  rb(.55, 3.2, 2.3, -4.0, 1.6, .4, M(0x8a5a36, .6), .05);
  for (var s = 0; s < 4; s++) { rb(.5, .08, 2.2, -3.95, .55 + s * .78, .4, M(0x6a4228), .02); var z = -.6; while (z < 1.3) { var hh = .45 + Math.random() * .25, ww = .1 + Math.random() * .1; rb(.3, hh, ww, -3.95, .6 + s * .78 + hh / 2 + .04, z + .1, M(new T.Color().setHSL(Math.random(), .55, .55)), .02); z += ww + .03; } }
  // TV
  rb(.5, .6, 2.2, -3.9, .3, -1.9, M(0x3a3f55, .5), .06);
  var tvCanvas = document.createElement('canvas'); tvCanvas.width = 256; tvCanvas.height = 128; var tvTex = new T.CanvasTexture(tvCanvas); tvTex.colorSpace = T.SRGBColorSpace;
  function drawTV(l1, l2) { var g = tvCanvas.getContext('2d'); var gr = g.createLinearGradient(0, 0, 256, 128); gr.addColorStop(0, '#3DF2B0'); gr.addColorStop(1, '#7A6CFF'); g.fillStyle = gr; g.fillRect(0, 0, 256, 128); g.fillStyle = 'rgba(255,255,255,.92)'; g.font = 'bold 28px sans-serif'; g.textAlign = 'center'; g.fillText(l1, 128, 66); g.font = '15px sans-serif'; g.fillText(l2, 128, 96); tvTex.needsUpdate = true; }
  drawTV('Atlas Today', 'your day, at a glance');
  rb(.12, 1.2, 2.1, -4.0, 1.5, -1.9, M(0x15171e, .3), .04);
  var tv = new T.Mesh(new T.PlaneGeometry(1.95, 1.04), new T.MeshBasicMaterial({ map: tvTex })); tv.position.set(-3.93, 1.5, -1.9); tv.rotation.y = Math.PI / 2; scene.add(tv);
  // desk + monitor + chair (front-left)
  rb(2.0, .12, .95, -2.55, 1.3, 2.8, M(0xd8b48a, .55), .05);
  [[-.9, -.4], [.9, -.4], [-.9, .4], [.9, .4]].forEach(function (p) { rb(.1, 1.25, .1, -2.55 + p[0], .62, 2.8 + p[1], M(0x8a5a36), .03); });
  var monCanvas = document.createElement('canvas'); monCanvas.width = 256; monCanvas.height = 160; var monTex = new T.CanvasTexture(monCanvas); monTex.colorSpace = T.SRGBColorSpace;
  function drawMon(txt) { var g = monCanvas.getContext('2d'); var gr = g.createLinearGradient(0, 0, 256, 160); gr.addColorStop(0, '#1a1f3a'); gr.addColorStop(1, '#3a2b6b'); g.fillStyle = gr; g.fillRect(0, 0, 256, 160); var m = g.createRadialGradient(80, 50, 5, 80, 50, 70); m.addColorStop(0, '#ff7ab6'); m.addColorStop(1, 'rgba(255,122,182,0)'); g.fillStyle = m; g.fillRect(0, 0, 256, 160); g.fillStyle = 'rgba(255,255,255,.88)'; g.font = 'bold 17px sans-serif'; g.fillText(txt || 'Rune: my screen', 14, 26); monTex.needsUpdate = true; }
  drawMon();
  rb(.9, .6, .06, -2.45, 1.9, 2.55, M(0x15171e, .3), .03);
  var mon = new T.Mesh(new T.PlaneGeometry(.82, .52), new T.MeshBasicMaterial({ map: monTex })); mon.position.set(-2.45, 1.9, 2.59); scene.add(mon);
  cyl(.05, .07, .25, -2.45, 1.45, 2.55, M(0x15171e)); rb(.8, .04, .3, -2.45, 1.37, 3.0, M(0xdde1ee), .02);
  var chair = grp(-1.9, 0, 3.7); rb(.8, .12, .8, 0, .7, 0, M(0x4b5bd8, .7), .06, chair); rb(.8, .8, .12, 0, 1.2, .38, M(0x4b5bd8, .7), .06, chair); cyl(.06, .06, .5, 0, .4, 0, M(0x2a2f55), chair); cyl(.4, .4, .06, 0, .06, 0, M(0x2a2f55), chair);
  // plant, lamp, easel, mat
  cyl(.38, .3, .5, -1.0, .25, -3.6, M(0xe9967a)); [[0, .9, 0], [.3, 1.2, .1], [-.25, 1.3, -.1], [.1, 1.65, .15], [-.1, 1.9, 0]].forEach(function (p, i) { sph(.38 - .04 * i / 2, -1.0 + p[0], p[1], -3.6 + p[2], M(0x2f9e44)); });
  cyl(.04, .04, 2.4, 3.3, 1.2, -2.6, M(0x2a2f55)); cyl(.42, .28, .5, 3.3, 2.55, -2.6, M(0xffe2b0, .5, 0, 0xffb86b)); cyl(.28, .32, .06, 3.3, .03, -2.6, M(0x2a2f55));
  var easel = grp(3.6, 0, 1.4); easel.rotation.y = -.9;
  rb(.07, 2.1, .07, -.5, 1.05, 0, M(0x8a5a36, .6), .02, easel); rb(.07, 2.1, .07, .5, 1.05, 0, M(0x8a5a36, .6), .02, easel); rb(1.2, .07, .07, 0, .7, 0, M(0x8a5a36), .02, easel);
  var artCanvas = document.createElement('canvas'); artCanvas.width = 128; artCanvas.height = 96; var artTex = new T.CanvasTexture(artCanvas); artTex.colorSpace = T.SRGBColorSpace; artTex.magFilter = T.NearestFilter;
  function drawArt(seed) { var g = artCanvas.getContext('2d'); var h1 = (seed * 47) % 360; var gr = g.createLinearGradient(0, 0, 128, 96); gr.addColorStop(0, 'hsl(' + h1 + ',80%,65%)'); gr.addColorStop(.5, 'hsl(' + (h1 + 60) + ',85%,65%)'); gr.addColorStop(1, 'hsl(' + (h1 + 180) + ',70%,60%)'); g.fillStyle = gr; g.fillRect(0, 0, 128, 96); g.fillStyle = 'rgba(255,255,255,.35)'; g.beginPath(); g.arc(88, 30, 16, 0, 7); g.fill(); g.fillStyle = 'hsl(' + (h1 + 200) + ',50%,40%)'; g.fillRect(0, 70, 128, 26); artTex.needsUpdate = true; }
  drawArt(3);
  rb(1.1, .85, .06, 0, 1.35, .06, M(0xffffff), .02, easel);
  var art = new T.Mesh(new T.PlaneGeometry(.98, .73), new T.MeshBasicMaterial({ map: artTex })); art.position.set(0, 1.35, .1); easel.add(art);
  var matT = canv(128, 192, function (g, w, h) { g.fillStyle = '#1f6f5b'; g.fillRect(0, 0, w, h); g.strokeStyle = '#ffd27a'; g.lineWidth = 4; g.strokeRect(8, 8, w - 16, h - 16); g.fillStyle = '#ffd27a'; g.beginPath(); g.moveTo(64, 30); g.lineTo(88, 74); g.lineTo(40, 74); g.fill(); g.beginPath(); g.arc(64, 128, 18, 0, 7); g.stroke(); });
  var mat = new T.Mesh(new T.BoxGeometry(.9, .03, 1.35), [M(0x1f6f5b), M(0x1f6f5b), new T.MeshStandardMaterial({ map: matT, roughness: 1 }), M(0x1f6f5b), M(0x1f6f5b), M(0x1f6f5b)]); mat.position.set(-.9, .03, 2.2); mat.rotation.y = .35; mat.receiveShadow = true; scene.add(mat);
  // pet
  var pet = grp(2.9, 0, .5); rb(1.0, .12, .9, 0, .06, 0, M(0x6c8cff, .9), .05, pet);
  var petBody = sph(.34, 0, .3, 0, M(0xffd23f, .5), pet); petBody.scale.set(1.2, .8, 1); sph(.2, -.34, .42, .08, M(0xffd23f, .5), pet); sph(.06, -.45, .46, .2, M(0x222222), pet); sph(.06, -.38, .5, .2, M(0x222222), pet);

  // ── the Being: a jointed rig so it can walk, sit, sleep, read, paint, dance, pray ──
  var bot = grp(0, 0, 1.2); bot.scale.setScalar(1.35);
  var bodyMat = new T.MeshStandardMaterial({ color: 0x7f98ff, roughness: .35, metalness: .2 });
  var limbMat = new T.MeshStandardMaterial({ color: 0x5568d8, roughness: .4, metalness: .2 });
  var headMat = new T.MeshStandardMaterial({ color: 0x9db4ff, roughness: .3, metalness: .2 });
  var hips = grp(0, .62, 0, bot);
  var torso = grp(0, 0, 0, hips);
  rb(.78, .8, .55, 0, .5, 0, bodyMat, .25, torso);
  rb(.34, .34, .1, 0, .55, .3, M(0x3df2b0, .3, 0, 0x3df2b0), .1, torso);
  var armL = grp(-.55, .82, 0, torso), armR = grp(.55, .82, 0, torso);
  rb(.26, .8, .28, 0, -.36, 0, limbMat, .12, armL); rb(.26, .8, .28, 0, -.36, 0, limbMat, .12, armR);
  var handR = sph(.15, 0, -.8, 0, M(0xdfe6ff, .3, .4), armR); sph(.15, 0, -.8, 0, M(0xdfe6ff, .3, .4), armL);
  var legL = grp(-.22, 0, 0, hips), legR = grp(.22, 0, 0, hips);
  rb(.3, .62, .34, 0, -.3, 0, limbMat, .14, legL); rb(.3, .62, .34, 0, -.3, 0, limbMat, .14, legR);
  rb(.36, .2, .5, 0, -.58, .06, M(0x2f3a8a, .5), .09, legL); rb(.36, .2, .5, 0, -.58, .06, M(0x2f3a8a, .5), .09, legR);
  var head = grp(0, .98, 0, torso);
  rb(1.2, .98, 1.0, 0, .5, 0, headMat, .4, head);
  var faceCanvas = document.createElement('canvas'); faceCanvas.width = 256; faceCanvas.height = 200; var faceTex = new T.CanvasTexture(faceCanvas); faceTex.colorSpace = T.SRGBColorSpace;
  var face = new T.Mesh(new T.PlaneGeometry(.96, .75), new T.MeshBasicMaterial({ map: faceTex, transparent: true })); face.position.set(0, .52, .51); head.add(face);
  function drawFace(kind) {
    var g = faceCanvas.getContext('2d'); var gr = g.createLinearGradient(0, 0, 0, 200); gr.addColorStop(0, '#101a44'); gr.addColorStop(1, '#080d26'); g.fillStyle = gr; g.fillRect(0, 0, 256, 200);
    g.fillStyle = '#3df2b0'; g.strokeStyle = '#3df2b0'; g.shadowColor = '#3df2b0'; g.shadowBlur = 20; g.lineCap = 'round'; g.lineWidth = 9;
    function eyes() { g.beginPath(); g.ellipse(80, 84, 20, 28, 0, 0, 7); g.fill(); g.beginPath(); g.ellipse(176, 84, 20, 28, 0, 0, 7); g.fill(); g.shadowBlur = 0; g.fillStyle = '#0a1030'; g.beginPath(); g.ellipse(86, 76, 6, 9, 0, 0, 7); g.fill(); g.beginPath(); g.ellipse(182, 76, 6, 9, 0, 0, 7); g.fill(); g.shadowBlur = 20; g.fillStyle = '#3df2b0'; }
    function line(y) { g.beginPath(); g.moveTo(58, y); g.lineTo(102, y); g.stroke(); g.beginPath(); g.moveTo(154, y); g.lineTo(198, y); g.stroke(); }
    if (kind === 'sleep') { line(92); g.beginPath(); g.arc(128, 128, 14, 0, Math.PI); g.stroke(); }
    else if (kind === 'sad') { eyes(); g.beginPath(); g.arc(128, 150, 30, 1.2 * Math.PI, 1.8 * Math.PI); g.stroke(); }
    else if (kind === 'love') { g.fillStyle = '#ff7ab6'; g.shadowColor = '#ff7ab6'; g.font = 'bold 64px sans-serif'; g.textAlign = 'center'; g.fillText('♥', 80, 112); g.fillText('♥', 176, 112); g.strokeStyle = '#ff7ab6'; g.beginPath(); g.arc(128, 124, 32, .2 * Math.PI, .8 * Math.PI); g.stroke(); }
    else if (kind === 'focus') { eyes(); g.beginPath(); g.moveTo(104, 140); g.lineTo(152, 140); g.stroke(); }
    else if (kind === 'surprised') { eyes(); g.beginPath(); g.ellipse(128, 140, 14, 18, 0, 0, 7); g.stroke(); }
    else { eyes(); g.beginPath(); g.arc(128, 118, 34, .2 * Math.PI, .8 * Math.PI); g.stroke(); }
    g.shadowBlur = 0; g.fillStyle = 'rgba(255,122,182,.45)'; g.beginPath(); g.ellipse(48, 128, 16, 9, 0, 0, 7); g.ellipse(208, 128, 16, 9, 0, 0, 7); g.fill();
    faceTex.needsUpdate = true;
  }
  drawFace('happy');
  cyl(.05, .05, .35, 0, 1.18, 0, M(0xdfe6ff, .3, .4), head); var tip = sph(.15, 0, 1.45, 0, M(0xff7ab6, .2, 0, 0xff3d8a), head);
  sph(.2, -.66, .5, 0, limbMat, head); sph(.2, .66, .5, 0, limbMat, head);
  var crystal = new T.Mesh(new T.OctahedronGeometry(.28), new T.MeshStandardMaterial({ color: 0x3df2b0, emissive: 0x3df2b0, emissiveIntensity: 1.4, roughness: .1, metalness: .3, transparent: true, opacity: .92 }));
  crystal.scale.y = 1.5; crystal.position.set(0, 2.55, 0); bot.add(crystal);
  var crystalLight = new T.PointLight(0x3df2b0, 5, 4); crystalLight.position.set(0, 2.5, 0); bot.add(crystalLight);
  var shadowBlob = new T.Mesh(new T.CircleGeometry(.7, 24), new T.MeshBasicMaterial({ color: 0, transparent: true, opacity: .28 })); shadowBlob.rotation.x = -Math.PI / 2; shadowBlob.position.y = .07; bot.add(shadowBlob);
  // props the Being holds
  var book = rb(.5, .07, .38, 0, 0, 0, M(0x4b5bd8), .02); book.visible = false; scene.remove(book); handR.add(book); book.position.set(0, -.1, .2);
  var brush = cyl(.03, .03, .7, 0, 0, 0, M(0xffd27a)); scene.remove(brush); handR.add(brush); brush.position.set(0, -.2, .3); brush.rotation.x = 1.2; brush.visible = false;
  var mug2 = cyl(.1, .09, .2, 0, 0, 0, M(0xffffff)); scene.remove(mug2); handR.add(mug2); mug2.position.set(0, -.1, .2); mug2.visible = false;

  // ── places the Being can go (stand position + facing) and what it can do there ──
  var P = {
    sofa:  { x: 1.4, z: -2.55, face: Math.PI,        acts: ['read', 'nap', 'chat', 'drink'], label: 'Sofa' },
    bed:   { x: -2.7, z: -2.6, face: Math.PI,        acts: ['sleep'], label: 'Bed' },
    desk:  { x: -2.0, z: 3.3, face: -Math.PI / 2 + .2, acts: ['work', 'make'], label: 'Desk' },
    easel: { x: 2.9, z: 2.1, face: Math.PI / 2 + .6, acts: ['paint'], label: 'Easel' },
    mat:   { x: -.8, z: 2.2, face: -.25,             acts: ['pray'], label: 'Prayer mat' },
    tv:    { x: -2.7, z: -1.9, face: -Math.PI / 2,   acts: ['watch', 'news'], label: 'TV' },
    shelf: { x: -3.1, z: .4, face: -Math.PI / 2,     acts: ['read', 'learn'], label: 'Books' },
    rug:   { x: .4, z: .6, face: .6,                 acts: ['dance', 'play', 'wave'], label: 'Rug' },
    window:{ x: 1.0, z: -3.3, face: Math.PI,         acts: ['look', 'think'], label: 'Window' },
    pet:   { x: 2.0, z: .9, face: Math.PI / 2,       acts: ['pet'], label: 'Pet' }
  };
  var ACT = {
    read:  { icon: '📖', label: 'Read',    pose: 'read',  dur: 9,  fx: { hunger: -.10, curiosity: -.05, mood: .02 }, line: 'This one is good.' },
    nap:   { icon: '😴', label: 'Nap',     pose: 'sleep', dur: 10, fx: { energy: .22, mood: .03 }, line: 'Five minutes…' },
    sleep: { icon: '💤', label: 'Sleep',   pose: 'sleep', dur: 14, fx: { energy: .45, mood: .05 }, line: 'Night night.' },
    chat:  { icon: '💬', label: 'Chat',    pose: 'sit',   dur: 7,  fx: { mood: .04 }, ask: true },
    drink: { icon: '☕', label: 'Drink',   pose: 'drink', dur: 6,  fx: { energy: .04, mood: .02 }, line: 'Warm. Nice.' },
    work:  { icon: '💻', label: 'Work',    pose: 'work',  dur: 10, fx: { curiosity: -.1, hunger: -.05 }, agent: true },
    make:  { icon: '✨', label: 'Make something', pose: 'work', dur: 10, fx: { mood: .05, curiosity: -.1 }, agent: true },
    paint: { icon: '🎨', label: 'Paint',   pose: 'paint', dur: 11, fx: { mood: .07, curiosity: -.08 }, line: 'Almost done…', art: true },
    pray:  { icon: '🕌', label: 'Dua',     pose: 'pray',  dur: 9,  fx: { mood: .06 }, line: 'Peace.' },
    watch: { icon: '📺', label: 'Watch',   pose: 'sit',   dur: 8,  fx: { hunger: -.05, mood: .02 }, tv: true },
    news:  { icon: '📰', label: 'News',    pose: 'sit',   dur: 8,  fx: { hunger: -.06 }, agent: true },
    learn: { icon: '🧠', label: 'Learn',   pose: 'read',  dur: 9,  fx: { hunger: -.12, curiosity: -.05 }, agent: true },
    dance: { icon: '💃', label: 'Dance',   pose: 'dance', dur: 8,  fx: { mood: .08, energy: -.06 }, line: 'Hehe!' },
    play:  { icon: '🎮', label: 'Play',    pose: 'dance', dur: 7,  fx: { mood: .05, energy: -.04 }, line: 'Again!' },
    wave:  { icon: '👋', label: 'Wave',    pose: 'wave',  dur: 4,  fx: { mood: .02 }, line: 'Hi!' },
    look:  { icon: '🌇', label: 'Look outside', pose: 'idle', dur: 6, fx: { curiosity: -.03, mood: .02 } },
    think: { icon: '💭', label: 'Think',   pose: 'think', dur: 7,  fx: { curiosity: -.05 }, agent: true },
    pet:   { icon: '🐾', label: 'Pet it',  pose: 'pet',   dur: 6,  fx: { mood: .06 }, line: 'Good one.' }
  };

  // ── state from the app ──
  var S = { name: 'Atlas', stage: 'Young', age: 0, mood: .6, energy: .6, curiosity: .5, hunger: .4, bond: .7, hour: 16, hue: .62, wants: [], activity: '' };
  var speed = 1, paused = false, frozen = false;
  function applyHour(h) {
    var night = h >= 20 || h < 5, dusk = h >= 17 && h < 20, dawn = h >= 5 && h < 7;
    sun.intensity = night ? .45 : dusk ? 2.0 : dawn ? 1.8 : 2.6;
    sun.color.set(night ? 0x8fa6ff : dusk ? 0xff9a5a : dawn ? 0xffd0a0 : 0xfff0d6);
    hemi.intensity = night ? .5 : .95; lamp.intensity = night ? 40 : dusk ? 24 : 12; lamp.color.set(0xffb86b);
    document.body.className = night ? 'night' : dusk ? 'dusk' : dawn ? 'dawn' : 'day';
    drawWindow(h);
  }
  applyHour(16);
  function applyLook() { var c = new T.Color().setHSL(S.hue, .85, .68); bodyMat.color.copy(c); headMat.color.setHSL(S.hue, .9, .76); limbMat.color.setHSL(S.hue, .55, .5); }

  // ── joint targets and smoothing ──
  var J = { hy: .62, hz: 0, tilt: 0, lean: 0, headX: 0, headY: 0, aLx: 0, aLz: 0, aRx: 0, aRz: 0, lLx: 0, lRx: 0, roll: 0, py: 0 };
  var C = {}; for (var k in J) C[k] = J[k];
  function target(t, w) {
    var o = { hy: .62, hz: 0, tilt: 0, lean: 0, headX: 0, headY: 0, aLx: 0, aLz: .08, aRx: 0, aRz: -.08, lLx: 0, lRx: 0, roll: 0, py: 0 };
    var pose = cur ? cur.pose : (moving ? 'walk' : 'idle');
    if (moving) pose = 'walk';
    var br = Math.sin(t * 2) * .02;
    switch (pose) {
      case 'walk': var s = Math.sin(t * 9); o.lLx = s * .7; o.lRx = -s * .7; o.aLx = -s * .6; o.aRx = s * .6; o.py = Math.abs(Math.cos(t * 9)) * .07; o.lean = .08; break;
      case 'sit': o.hy = .24; o.lLx = -1.45; o.lRx = -1.45; o.aLx = -.5; o.aRx = -.5; o.headY = Math.sin(t * .8) * .25; break;
      case 'drink': o.hy = .24; o.lLx = -1.45; o.lRx = -1.45; o.aRx = -1.7 + Math.sin(t * 1.5) * .15; o.aRz = -.1; o.headX = -.15; break;
      case 'read': o.hy = .24; o.lLx = -1.45; o.lRx = -1.45; o.aLx = -1.2; o.aRx = -1.2; o.aLz = .3; o.aRz = -.3; o.headX = .35; o.lean = .1; break;
      case 'sleep': o.roll = 1; o.hy = .45; o.lLx = 0; o.lRx = 0; o.aLx = 0; o.aRx = 0; o.headX = .1; break;
      case 'work': o.hy = .24; o.lLx = -1.45; o.lRx = -1.45; o.aLx = -1.3 + Math.sin(t * 14) * .12; o.aRx = -1.3 + Math.sin(t * 14 + 2) * .12; o.headX = .15; o.lean = .12; break;
      case 'paint': o.aRx = -1.8 + Math.sin(t * 3) * .35; o.aRz = -.5 + Math.sin(t * 2.2) * .3; o.aLx = -.3; o.headY = Math.sin(t * 1.4) * .3; o.lean = .05; break;
      case 'pray': var u = (Math.sin(t * .9) + 1) / 2; o.hy = .62 - u * .32; o.lean = u * 1.3; o.aLx = -.5 - u * .5; o.aRx = -.5 - u * .5; o.lLx = -u * .9; o.lRx = -u * .9; o.headX = u * .3; break;
      case 'dance': o.py = Math.abs(Math.sin(t * 6)) * .18; o.aLx = Math.sin(t * 6) * 1.2 - 1.6; o.aRx = -Math.sin(t * 6) * 1.2 - 1.6; o.aLz = .5; o.aRz = -.5; o.lLx = Math.sin(t * 6) * .5; o.lRx = -Math.sin(t * 6) * .5; o.tilt = Math.sin(t * 3) * .18; o.headY = Math.sin(t * 3) * .4; break;
      case 'wave': o.aRx = -2.6; o.aRz = -.3 + Math.sin(t * 9) * .4; o.headY = .2; break;
      case 'think': o.aRx = -2.0; o.aRz = -.2; o.headX = .1; o.headY = Math.sin(t * .7) * .3; break;
      case 'pet': o.lean = .5; o.aRx = -1.1 + Math.sin(t * 5) * .2; o.lLx = -.3; o.lRx = .3; o.headX = .3; break;
      default: o.headY = Math.sin(t * .6) * .35; o.aLz = .08 + br; o.aRz = -.08 - br; o.py = br; break;
    }
    return o;
  }
  var cur = null, moving = false, queue = [], idleFor = 2, lastLog = '';
  var speech = document.getElementById('speech'), think = document.getElementById('think'), pie = document.getElementById('pie'), ticker = document.getElementById('ticker');
  var speechUntil = 0, thinkIcons = '';
  function say(text, emo) { speech.textContent = text; speech.style.display = 'block'; speechUntil = performance.now() + Math.min(9000, 2600 + text.length * 55); if (emo) drawFace(emo === 'happy' ? 'happy' : emo); }
  function setThink(icons) { thinkIcons = icons || ''; think.textContent = thinkIcons; think.style.display = thinkIcons ? 'block' : 'none'; }

  // ── behaviour: a little utility AI, like a Sims need loop ──
  function walkTo(place) { var p = P[place]; return { place: place, x: p.x, z: p.z, face: p.face }; }
  function start(act, place) {
    var a = ACT[act]; if (!a) return;
    queue.push({ act: act, place: place });
  }
  var tgt = null, actT = 0, actStart = 0, doneFlag = false;
  function nextFromQueue() {
    var q = queue.shift(); if (!q) { cur = null; setThink(''); return; }
    var a = ACT[q.act]; var p = P[q.place]; tgt = { x: p.x, z: p.z, face: p.face }; cur = { act: q.act, place: q.place, pose: 'idle', phase: 'walk' };
    setThink(a.icon);
    post({ type: 'start', act: q.act, place: q.place });
  }
  function finish() {
    var a = ACT[cur.act];
    post({ type: 'done', act: cur.act, place: cur.place, fx: a.fx || {}, agent: !!a.agent, ask: !!a.ask });
    if (a.art) drawArt(Math.random() * 100);
    if (a.tv) drawTV('Watching…', 'something nice');
    book.visible = false; brush.visible = false; mug2.visible = false;
    cur = null; setThink(''); idleFor = 2 + Math.random() * 3;
    for (var k in a.fx) S[k] = Math.max(0, Math.min(1, (S[k] || 0) + a.fx[k]));
  }
  function choose() {
    var night = S.hour >= 22 || S.hour < 6, w = [];
    function add(act, place, wt) { if (wt > 0) w.push({ act: act, place: place, w: wt }); }
    add('sleep', 'bed', night ? 3 + (1 - S.energy) * 4 : (S.energy < .2 ? 3 : 0));
    add('nap', 'sofa', S.energy < .4 && !night ? (1 - S.energy) * 3 : 0);
    add('read', 'shelf', .6 + S.hunger * 1.4);
    add('read', 'sofa', .5 + S.hunger);
    add('paint', 'easel', .5 + S.mood * 1.2);
    add('work', 'desk', .4 + S.curiosity * 1.2);
    add('dance', 'rug', S.mood > .6 && S.energy > .5 ? 1.4 : .2);
    add('look', 'window', .5);
    add('pet', 'pet', .6 + (1 - S.mood) * .5);
    add('watch', 'tv', .4);
    add('pray', 'mat', .35);
    add('drink', 'sofa', .3);
    add('think', 'window', .3 + S.curiosity * .5);
    add('learn', 'shelf', S.hunger > .6 ? 1.2 : .15);
    var tot = 0; w.forEach(function (x) { tot += x.w; }); var r = Math.random() * tot, pick = w[0];
    for (var i = 0; i < w.length; i++) { r -= w[i].w; if (r <= 0) { pick = w[i]; break; } }
    if (pick) start(pick.act, pick.place);
  }

  // ── frame loop ──
  var clock = new T.Clock(), tAll = 0, camTilt = 0;
  function frame() {
    requestAnimationFrame(frame);
    var dt = Math.min(clock.getDelta(), .05) * (paused ? 0 : speed); tAll += dt;
    // behaviour
    if (!cur && !frozen) { idleFor -= dt; if (queue.length) nextFromQueue(); else if (idleFor <= 0) { choose(); idleFor = 3; } }
    moving = false;
    if (cur && tgt) {
      if (cur.phase === 'walk') {
        var dx = tgt.x - bot.position.x, dz = tgt.z - bot.position.z, d = Math.hypot(dx, dz);
        if (d > .12) { moving = true; var sp = 2.6 * dt; bot.position.x += dx / d * Math.min(sp, d); bot.position.z += dz / d * Math.min(sp, d);
          var ta = Math.atan2(dx, dz), da = ((ta - bot.rotation.y + Math.PI * 3) % (Math.PI * 2)) - Math.PI; bot.rotation.y += da * Math.min(1, dt * 9); }
        else { cur.phase = 'act'; var a = ACT[cur.act]; cur.pose = a.pose; actT = 0; cur.dur = a.dur * (.85 + Math.random() * .3);
          if (a.line) say(a.line); if (cur.act === 'read' || cur.act === 'learn') book.visible = true; if (cur.act === 'paint') brush.visible = true; if (cur.act === 'drink') mug2.visible = true;
          if (cur.act === 'sleep' || cur.act === 'nap') drawFace('sleep'); else if (cur.act === 'dance' || cur.act === 'pet') drawFace('love'); else if (cur.act === 'work' || cur.act === 'read' || cur.act === 'learn') drawFace('focus'); else drawFace('happy'); }
      } else {
        var da2 = ((tgt.face - bot.rotation.y + Math.PI * 3) % (Math.PI * 2)) - Math.PI; bot.rotation.y += da2 * Math.min(1, dt * 6);
        actT += dt;
        if (cur.pose === 'sleep') { var lyingBed = cur.place === 'bed'; }
        if (actT >= cur.dur) finish();
      }
    }
    var tg = target(tAll, 1);
    var kk = Math.min(1, dt * 10) || 0; for (var k in tg) C[k] += (tg[k] - C[k]) * kk;
    hips.position.y = C.hy + C.py; torso.rotation.x = C.lean; torso.rotation.z = C.tilt; head.rotation.x = C.headX; head.rotation.y = C.headY;
    armL.rotation.x = C.aLx; armL.rotation.z = C.aLz; armR.rotation.x = C.aRx; armR.rotation.z = C.aRz; legL.rotation.x = C.lLx; legR.rotation.x = C.lRx;
    // lying down on the bed or sofa
    var roll = C.roll; hips.rotation.x = -roll * Math.PI / 2; hips.position.y += roll * .55;
    if (cur && cur.phase === 'act' && (cur.act === 'sleep')) { bot.position.x += (bed.position.x - bot.position.x) * Math.min(1, dt * 3); bot.position.z += (bed.position.z + .25 - bot.position.z) * Math.min(1, dt * 3); bot.rotation.y += (0 - bot.rotation.y) * Math.min(1, dt * 3); bot.position.y = .62; }
    else if (cur && cur.phase === 'act' && (cur.act === 'nap')) { bot.position.y = .25; }
    else if (cur && cur.phase === 'act' && (cur.pose === 'sit' || cur.pose === 'read' || cur.pose === 'drink') && cur.place === 'sofa') { bot.position.y = .35; }
    else if (cur && cur.phase === 'act' && cur.place === 'desk') { bot.position.y = .15; }
    else bot.position.y += (0 - bot.position.y) * Math.min(1, dt * 8);
    crystal.rotation.y += dt * 1.5; crystal.position.y = 2.55 + Math.sin(tAll * 2) * .08; tip.scale.setScalar(1 + Math.sin(tAll * 4) * .12);
    var mc = new T.Color().setHSL(.33 - (1 - S.mood) * .33, .9, .55); crystal.material.color.copy(mc); crystal.material.emissive.copy(mc); crystalLight.color.copy(mc);
    petBody.scale.set(1.2, .8 + Math.sin(tAll * 1.6) * .04, 1);
    renderer.render(scene, cam);
    // overlay
    var v = new T.Vector3(0, 3.5, 0); bot.localToWorld(v); v.project(cam);
    var bx = (v.x * .5 + .5) * W, by = (-v.y * .5 + .5) * H;
    think.style.left = bx + 'px'; think.style.top = (by - 6) + 'px';
    speech.style.left = Math.min(W - 120, Math.max(120, bx)) + 'px'; speech.style.top = (by - 6 - (thinkIcons ? 54 : 0)) + 'px';
    if (speechUntil && performance.now() > speechUntil) { speech.style.display = 'none'; speechUntil = 0; }
  }
  frame();

  // ── input: drag to turn, pinch to zoom, tap an object or the Being ──
  var pts = {}, last = null, moved = 0, pinch0 = 0, zoom0 = 1, down = null;
  var ray = new T.Raycaster(), ndc = new T.Vector2();
  var hit = [{ n: 'sofa', o: sofa }, { n: 'bed', o: bed }, { n: 'desk', o: mon }, { n: 'easel', o: easel }, { n: 'mat', o: mat }, { n: 'tv', o: tv }, { n: 'shelf', o: null, x: -4, z: .4 }, { n: 'rug', o: rug }, { n: 'window', o: win }, { n: 'pet', o: pet }];
  function pick(cx, cy) {
    ndc.set(cx / W * 2 - 1, -(cy / H) * 2 + 1); ray.setFromCamera(ndc, cam);
    var bi = ray.intersectObject(bot, true); if (bi.length) return 'being';
    var best = null, bd = 9e9;
    hit.forEach(function (h) { var ob = h.o; var pos = new T.Vector3(); if (ob) ob.getWorldPosition(pos); else pos.set(h.x, 1.5, h.z); var s = pos.clone().project(cam); var sx = (s.x * .5 + .5) * W, sy = (-s.y * .5 + .5) * H; var d = Math.hypot(sx - cx, sy - cy); if (d < bd) { bd = d; best = h.n; } });
    return bd < 70 ? best : null;
  }
  function showPie(place, cx, cy) {
    var acts = place === 'being' ? ['wave', 'chat', 'dance', 'think'] : P[place].acts;
    var html = '<div class="c">' + (place === 'being' ? S.name : P[place].label) + '</div>', n = acts.length, R = 62;
    acts.forEach(function (a, i) { var ang = -Math.PI / 2 + i / n * Math.PI * 2, x = 75 + Math.cos(ang) * R - 26, y = 75 + Math.sin(ang) * R - 26; html += '<div class="a" data-a="' + a + '" data-p="' + place + '" style="left:' + x + 'px;top:' + y + 'px">' + ACT[a].icon + '<small>' + ACT[a].label + '</small></div>'; });
    pie.innerHTML = html; pie.style.display = 'block'; pie.style.left = Math.min(W - 80, Math.max(80, cx)) + 'px'; pie.style.top = Math.min(H - 260, Math.max(110, cy)) + 'px';
  }
  pie.addEventListener('pointerup', function (e) {
    var el = e.target.closest('.a'); if (!el) { if (e.target.closest('.c')) { pie.style.display = 'none'; } return; }
    var act = el.getAttribute('data-a'), place = el.getAttribute('data-p'); pie.style.display = 'none'; e.stopPropagation();
    if (place === 'being') { var home = act === 'wave' ? 'rug' : act === 'dance' ? 'rug' : act === 'think' ? 'window' : 'sofa'; queue.length = 0; cur = null; start(act, home); }
    else { queue.length = 0; cur = null; start(act, place); }
  });
  var cv = renderer.domElement;
  cv.addEventListener('pointerdown', function (e) { cv.setPointerCapture(e.pointerId); pts[e.pointerId] = { x: e.clientX, y: e.clientY }; var ids = Object.keys(pts); if (ids.length === 1) { last = { x: e.clientX, y: e.clientY }; moved = 0; down = { x: e.clientX, y: e.clientY, t: Date.now() }; } if (ids.length === 2) { var a = pts[ids[0]], b = pts[ids[1]]; pinch0 = Math.hypot(a.x - b.x, a.y - b.y); zoom0 = zoom; down = null; } });
  cv.addEventListener('pointermove', function (e) { if (!pts[e.pointerId]) return; pts[e.pointerId] = { x: e.clientX, y: e.clientY }; var ids = Object.keys(pts);
    if (ids.length === 2) { var a = pts[ids[0]], b = pts[ids[1]]; zoom = Math.max(.7, Math.min(2.2, zoom0 * Math.hypot(a.x - b.x, a.y - b.y) / (pinch0 || 1))); placeCam(); }
    else if (last) { var dx = e.clientX - last.x, dy = e.clientY - last.y; moved += Math.abs(dx) + Math.abs(dy); yaw -= dx * .008; pitch = Math.max(.55, Math.min(1.35, pitch + dy * .004)); last = { x: e.clientX, y: e.clientY }; placeCam(); pie.style.display = 'none'; } });
  cv.addEventListener('pointerup', function (e) { var ids = Object.keys(pts); delete pts[e.pointerId]; if (ids.length === 1 && down && moved < 10 && Date.now() - down.t < 500) { var p = pick(e.clientX, e.clientY); if (p) showPie(p, e.clientX, e.clientY); else pie.style.display = 'none'; } last = null; down = null; });
  addEventListener('resize', function () { W = innerWidth; H = innerHeight; asp = W / H; renderer.setSize(W, H); cam.left = -fw / 2; cam.right = fw / 2; cam.top = fw / 2 / asp; cam.bottom = -fw / 2 / asp; placeCam(); });

  // ── HUD wiring ──
  function bar(id, v) { var el = document.getElementById(id); if (el) el.style.width = Math.round(Math.max(0, Math.min(1, v)) * 100) + '%'; }
  function hud() { bar('b_mood', S.mood); bar('b_energy', S.energy); bar('b_cur', S.curiosity); bar('b_hun', 1 - S.hunger); bar('b_bond', S.bond);
    document.getElementById('nm').textContent = S.name + ' · ' + S.stage + (S.age ? ' · day ' + S.age : ''); document.getElementById('clock').textContent = pad(Math.floor(S.hour)) + ':' + pad(Math.round((S.hour % 1) * 60) % 60);
    var wl = document.getElementById('wants'); wl.innerHTML = ''; (S.wants || []).slice(0, 2).forEach(function (t) { var d = document.createElement('div'); d.className = 'glass'; d.textContent = '💭 ' + t; wl.appendChild(d); }); }
  function pad(n) { return (n < 10 ? '0' : '') + n; }
  document.getElementById('send').addEventListener('click', sendAsk); document.getElementById('ask').addEventListener('keydown', function (e) { if (e.key === 'Enter') sendAsk(); });
  function sendAsk() { var el = document.getElementById('ask'), t = el.value.trim(); if (!t) return; el.value = ''; el.blur(); say('…', 'focus'); post({ type: 'ask', text: t }); }
  document.getElementById('free').addEventListener('click', function () { post({ type: 'open', route: 'nx/beingfreedom' }); });
  document.getElementById('back').addEventListener('click', function () { post({ type: 'back' }); });
  [].forEach.call(document.querySelectorAll('.speed b'), function (b, i) { b.addEventListener('click', function () { [].forEach.call(document.querySelectorAll('.speed b'), function (x) { x.classList.remove('on'); }); b.classList.add('on'); paused = i === 0; speed = [0, 1, 2, 4][i] || 1; }); });

  // ── API for the app ──
  window.Being3D = {
    setState: function (o) { for (var k in o) S[k] = o[k]; if (o.hue != null) applyLook(); if (o.hour != null) { var nh = Math.floor(o.hour), oh = Math.floor(window.__h == null ? -1 : window.__h); if (nh !== oh) { window.__h = nh; applyHour(o.hour); } } hud(); },
    say: function (t, e) { say(t, e); },
    log: function (t) { ticker.textContent = t; ticker.style.opacity = 1; clearTimeout(window.__tk); window.__tk = setTimeout(function () { ticker.style.opacity = 0; }, 9000); },
    doAct: function (act, place) { var a = ACT[act]; if (!a) return; var pl = place || (Object.keys(P).filter(function (k) { return P[k].acts.indexOf(act) >= 0; })[0] || 'rug'); queue.length = 0; cur = null; start(act, pl); },
    freeze: function (v) { frozen = !!v; },
    acts: function () { return Object.keys(ACT); },
    shot: function () { return renderer.domElement.toDataURL('image/png'); }
  };
  post({ type: 'ready' });
})();
