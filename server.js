const express = require('express');
const http = require('http');
const { Server } = require('socket.io');

// Create app and server
const app = express();
const server = http.createServer(app);
const io = new Server(server);

// Serve static files from public directory
app.use(express.static('public'));

// Holds state for the single room. In a more complete implementation
// you could support multiple rooms by keying off of a room ID.
const state = {
  players: [],        // Array of { id: socket.id, name: string, x: number, y: number, alive: bool, infected: bool }
  host: null,         // socket.id of the host
  gameRunning: false, // Is a mini‑game currently running?
  currentGame: null,  // String identifier of the current mini game
  scores: {},         // Map from socket.id to number of wins
  language: 'en'      // Host selected language for UI. 'en' or 'de'. Set via query param in the URL.
};

// Mini‑game definitions used on the server side. The client also has
// definitions but the server needs to know when games end to update
// scores. Each mini game will emit a 'gameOver' event with the
// winner's socket id, which we handle here.
const miniGames = ['laser', 'infected', 'colorRush', 'mazeRunner'];

io.on('connection', (socket) => {
  console.log('A user connected', socket.id);

  // When a player joins they send their chosen name and optionally a
  // preferred language. The first player becomes the host.
  socket.on('join', ({ name, lang }) => {
    // If host hasn't been set, this player is the host
    if (!state.host) {
      state.host = socket.id;
    }
    state.language = lang || state.language;
    // Prevent more than 5 players
    if (state.players.length >= 5) {
      socket.emit('errorMessage', {
        en: 'Lobby is full',
        de: 'Lobby ist voll'
      }[state.language]);
      return;
    }
    // Register player
    state.players.push({
      id: socket.id,
      name: name || 'Player',
      x: 0.5,
      y: 0.5,
      alive: true,
      infected: false
    });
    state.scores[socket.id] = state.scores[socket.id] || 0;
    // Broadcast updated lobby
    io.emit('lobbyUpdate', {
      players: state.players,
      host: state.host,
      scores: state.scores,
      language: state.language
    });
  });

  // The host initiates the spin to select a random mini game. This
  // event instructs all clients to start a spinner animation. Once
  // the spinner has ended on the client side it will emit
  // 'gameSelected' back to the server to confirm. This helps keep
  // client and server in sync.
  socket.on('startSpin', () => {
    if (socket.id !== state.host || state.gameRunning) return;
    const game = miniGames[Math.floor(Math.random() * miniGames.length)];
    state.currentGame = game;
    io.emit('spinStarted', { game });
  });

  // When the client has completed the spinner animation they call
  // 'gameSelected' so the server can update state and let clients
  // know to show instructions.
  socket.on('gameSelected', () => {
    if (!state.currentGame) return;
    io.emit('showInstructions', { game: state.currentGame, language: state.language });
  });

  // The host triggers the start of the actual mini game after
  // instructions. This event will also reset per‑game state on
  // the server such as player positions and statuses.
  socket.on('startGame', () => {
    if (socket.id !== state.host || state.gameRunning || !state.currentGame) return;
    // Reset positions and statuses
    state.players.forEach(p => {
      p.alive = true;
      p.infected = false;
      // Start players in the centre of the play area
      p.x = Math.random() * 0.6 + 0.2; // random pos to reduce stacking
      p.y = Math.random() * 0.6 + 0.2;
    });
    // If infected game pick one infected at random
    if (state.currentGame === 'infected') {
      const idx = Math.floor(Math.random() * state.players.length);
      state.players[idx].infected = true;
    }
    state.gameRunning = true;
    io.emit('gameStarted', { game: state.currentGame, players: state.players });
  });

  // Players send their movement updates so everyone stays in sync
  socket.on('updatePosition', ({ x, y }) => {
    const player = state.players.find(p => p.id === socket.id);
    if (player && state.gameRunning) {
      player.x = x;
      player.y = y;
      io.emit('playerMoved', { id: socket.id, x, y });
    }
  });

  // Clients emit when a game round is over to indicate winners and update
  // scoreboard. The payload contains an array of surviving player ids
  // (winners). We increment their score. Then we broadcast scoreboard
  // and set gameRunning false, currentGame null.
  socket.on('gameOver', ({ winners }) => {
    if (!state.gameRunning) return;
    state.gameRunning = false;
    // increment scores
    if (winners && Array.isArray(winners)) {
      winners.forEach(id => {
        if (state.scores[id] !== undefined) {
          state.scores[id] += 1;
        }
      });
    }
    // Notify clients
    io.emit('roundOver', { winners, scores: state.scores });
    state.currentGame = null;
  });

  // Handle disconnection
  socket.on('disconnect', () => {
    console.log('User disconnected', socket.id);
    // Remove from players list
    const index = state.players.findIndex(p => p.id === socket.id);
    if (index !== -1) {
      const wasHost = state.players[index].id === state.host;
      state.players.splice(index, 1);
      delete state.scores[socket.id];
      // If host left assign new host if players remain
      if (wasHost && state.players.length > 0) {
        state.host = state.players[0].id;
      }
      if (state.players.length === 0) {
        // Reset state completely when everyone leaves
        state.host = null;
        state.currentGame = null;
        state.gameRunning = false;
        state.scores = {};
      }
      io.emit('lobbyUpdate', {
        players: state.players,
        host: state.host,
        scores: state.scores,
        language: state.language
      });
    }
  });
});

// Start server on port provided by environment (for vercel) or 3000
const port = process.env.PORT || 3000;
server.listen(port, () => {
  console.log(`Server listening on port ${port}`);
});