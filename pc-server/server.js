const express = require('express');
const http = require('express'); // We will use native http & https modules
const httpModule = require('http');
const httpsModule = require('https');
const WebSocket = require('ws');
const path = require('path');
const fs = require('fs');

const app = express();
const httpPort = 3001;
const httpsPort = 3002;

// Serve static files from the 'public' directory
app.use(express.static(path.join(__dirname, 'public')));

// Root route redirects to PC Receiver dashboard
app.get('/', (req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

// Create HTTP server (primarily for the PC Receiver dashboard)
const httpServer = httpModule.createServer(app);

// Create HTTPS server with self-signed SSL/TLS certificates (primarily for the Mobile Client)
let httpsServer = null;
let sslOptions = null;

try {
  // Try loading 'selfsigned' to dynamically generate certs on startup
  const selfsigned = require('selfsigned');
  console.log('Generating dynamic self-signed SSL/TLS certificates...');
  
  const attrs = [{ name: 'commonName', value: 'localhost' }];
  const pems = selfsigned.generate(attrs, {
    days: 365,
    keySize: 2048,
    algorithm: 'sha256'
  });
  
  sslOptions = {
    key: pems.private,
    cert: pems.cert
  };
  
  // Persist them to disk for debugging / manual trust if needed
  fs.writeFileSync(path.join(__dirname, 'key.pem'), pems.private);
  fs.writeFileSync(path.join(__dirname, 'cert.pem'), pems.cert);
  console.log('SSL/TLS key.pem and cert.pem generated and persisted to disk.');
} catch (err) {
  console.log('Could not generate certs dynamically using selfsigned package. Falling back to file checks...', err.message);
  
  const keyPath = path.join(__dirname, 'key.pem');
  const certPath = path.join(__dirname, 'cert.pem');
  
  if (fs.existsSync(keyPath) && fs.existsSync(certPath)) {
    sslOptions = {
      key: fs.readFileSync(keyPath),
      cert: fs.readFileSync(certPath)
    };
    console.log('Loaded existing SSL/TLS certificate files from disk.');
  } else {
    console.error('CRITICAL WARNING: SSL/TLS certificates could not be generated or loaded. HTTPS server will NOT start.');
  }
}

if (sslOptions) {
  httpsServer = httpsModule.createServer(sslOptions, app);
}

// Create a single unified WebSocket server
const wss = new WebSocket.Server({ noServer: true });

// Manage WebSocket upgrades for BOTH HTTP (3001) and HTTPS (3002) ports
function setupUpgradeHandler(serverInstance) {
  if (!serverInstance) return;
  serverInstance.on('upgrade', (request, socket, head) => {
    wss.handleUpgrade(request, socket, head, (ws) => {
      wss.emit('connection', ws, request);
    });
  });
}

setupUpgradeHandler(httpServer);
setupUpgradeHandler(httpsServer);

// Keep track of connected clients
let senderSocket = null;
let receiverSocket = null;

wss.on('connection', (ws) => {
  console.log('New WebSocket connection established.');

  ws.on('message', (message) => {
    try {
      const data = JSON.parse(message);
      
      switch (data.type) {
        case 'register':
          if (data.role === 'sender') {
            senderSocket = ws;
            ws.role = 'sender';
            console.log('Mobile Sender registered.');
            // If receiver is already waiting, notify them
            if (receiverSocket) {
              receiverSocket.send(JSON.stringify({ type: 'sender-status', online: true }));
              // Automatically initiate WebRTC connection
              senderSocket.send(JSON.stringify({ type: 'initiate' }));
            }
          } else if (data.role === 'receiver') {
            receiverSocket = ws;
            ws.role = 'receiver';
            console.log('PC Receiver registered.');
            // Send current sender status to receiver
            const senderOnline = senderSocket !== null;
            receiverSocket.send(JSON.stringify({ type: 'sender-status', online: senderOnline }));
            
            // If sender is online, ask sender to initiate WebRTC SDP offer
            if (senderOnline) {
              senderSocket.send(JSON.stringify({ type: 'initiate' }));
            }
          }
          break;

        case 'offer':
        case 'answer':
        case 'ice-candidate':
          // Relay signaling messages to the opposite peer
          if (ws.role === 'sender' && receiverSocket) {
            receiverSocket.send(JSON.stringify(data));
          } else if (ws.role === 'receiver' && senderSocket) {
            senderSocket.send(JSON.stringify(data));
          }
          break;

        default:
          console.warn('Unknown message type received:', data.type);
      }
    } catch (error) {
      console.error('Error processing message:', error);
    }
  });

  ws.on('close', () => {
    if (ws.role === 'sender') {
      console.log('Mobile Sender disconnected.');
      senderSocket = null;
      if (receiverSocket) {
        receiverSocket.send(JSON.stringify({ type: 'sender-status', online: false }));
        receiverSocket.send(JSON.stringify({ type: 'peer-disconnected' }));
      }
    } else if (ws.role === 'receiver') {
      console.log('PC Receiver disconnected.');
      receiverSocket = null;
      if (senderSocket) {
        senderSocket.send(JSON.stringify({ type: 'peer-disconnected' }));
      }
    }
  });

  ws.on('error', (err) => {
    console.error('WebSocket Error:', err);
  });
});

// Start listening
httpServer.listen(httpPort, '0.0.0.0', () => {
  console.log(`=======================================================`);
  console.log(` 🚀 Webcam Streamer Server is running!`);
  console.log(` -----------------------------------------------------`);
  console.log(` 💻 PC Dashboard (HTTP): http://localhost:${httpPort}`);
  console.log(` 📱 Mobile Client (HTTPS):`);
  console.log(`    Address: https://<YOUR_PC_IP_ADDRESS>:${httpsPort}/mobile.html`);
  console.log(`=======================================================`);
});

if (httpsServer) {
  httpsServer.listen(httpsPort, '0.0.0.0', () => {
    console.log(` 🛡️  Secure HTTPS server active on port ${httpsPort} for mobile stream.`);
    console.log(`=======================================================`);
  });
} else {
  console.error(` ⚠️  HTTPS server could not be started. WebRTC on mobile might fail.`);
  console.log(`=======================================================`);
}
