/**
 * Zero-Dependency Air-Gapped Local Static Server
 * Serves the Live Transcribe PWA locally over LAN so Android devices can connect.
 */

const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');

const PORT = process.env.PORT || 3000;
const PUBLIC_DIR = path.join(__dirname, 'public');

const MIME_TYPES = {
  '.html': 'text/html; charset=UTF-8',
  '.css': 'text/css; charset=UTF-8',
  '.js': 'application/javascript; charset=UTF-8',
  '.json': 'application/manifest+json; charset=UTF-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.ico': 'image/x-icon'
};

function getLocalIpAddresses() {
  const interfaces = os.networkInterfaces();
  const addresses = [];
  for (const name of Object.keys(interfaces)) {
    for (const net of interfaces[name]) {
      if (net.family === 'IPv4' && !net.internal) {
        addresses.push(net.address);
      }
    }
  }
  return addresses;
}

const server = http.createServer((req, res) => {
  // Disallow non-GET
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    res.writeHead(405, { 'Content-Type': 'text/plain' });
    res.end('Method Not Allowed');
    return;
  }

  let reqPath = decodeURI(req.url.split('?')[0]);
  if (reqPath === '/' || reqPath === '') {
    reqPath = '/index.html';
  }

  const safePath = path.normalize(reqPath).replace(/^(\.\.[\/\\])+/, '');
  const filePath = path.join(PUBLIC_DIR, safePath);

  // Security check: ensure path stays within PUBLIC_DIR
  if (!filePath.startsWith(PUBLIC_DIR)) {
    res.writeHead(403, { 'Content-Type': 'text/plain' });
    res.end('Access Denied');
    return;
  }

  fs.stat(filePath, (err, stats) => {
    if (err || !stats.isFile()) {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      res.end('Not Found');
      return;
    }

    const ext = path.extname(filePath).toLowerCase();
    const contentType = MIME_TYPES[ext] || 'application/octet-stream';

    res.writeHead(200, {
      'Content-Type': contentType,
      'Content-Length': stats.size,
      'Cache-Control': 'no-cache',
      'X-Content-Type-Options': 'nosniff',
      'X-Frame-Options': 'DENY'
    });

    const stream = fs.createReadStream(filePath);
    stream.pipe(res);
  });
});

server.listen(PORT, '0.0.0.0', () => {
  const localIps = getLocalIpAddresses();
  const wifiIp = localIps.find(ip => ip.startsWith('10.') || ip.startsWith('192.168.')) || localIps[0] || 'localhost';

  console.log('\n================================================================');
  console.log('   TRASCRIZIONE ISTANTANEA (OFFLINE) PRONTA!');
  console.log('================================================================');
  console.log('COME INSTALLARLA SUL TELEFONO DELLA TUA RAGAZZA (10 SECONDI):');
  console.log('');
  console.log(`1. Sul telefono della tua ragazza, apri Chrome e vai a:`);
  console.log(`   👉 http://${wifiIp}:${PORT}`);
  console.log('');
  console.log('2. In alto a destra su Chrome, tocca i tre puntini (...)');
  console.log('3. Tocca "Aggiungi a schermata Home" (o "Installa applicazione")');
  console.log('');
  console.log('================================================================');
  console.log('FATTO! L\'app è ora salvata dentro il suo telefono.');
  console.log('A questo punto puoi spegnere il computer e disattivare il Wi-Fi.');
  console.log('L\'app funzionerà per sempre da sola, al 100% offline.');
  console.log('================================================================\n');
});

module.exports = server;
