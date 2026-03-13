const express = require('express');
const http = require('http');
const { Server } = require('socket.io');

// Create app and server
const app = express();
const server = http.createServer(app);
// Enable CORS so the Socket.IO server can accept connections from
// other origins (e.g. if the frontend is deployed separately). This
// helps during local development and when hosting the frontend and
// backend on different domains.
const io = new Server(server, {
  cors: {
    origin: '*'
  }
});

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
  ,soloLeaderboard: [] // Array of { name: string, score: number } for solo games
  ,soloMode: false
  ,startTime: null
  ,maxMazeTime: 120000
};

// Mini‑game definitions used on the server side. The client also has
// definitions but the server needs to know when games end to update
// scores. Each mini game will emit a 'gameOver' event with the
// winner's socket id, which we handle here.
const miniGames = ['laser', 'infected', 'colorRush', 'mazeRunner'];
// Games that can be played in solo mode (infected doesn't make sense alone)
const soloGames = ['laser', 'colorRush', 'mazeRunner'];

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

    // Send current solo leaderboard to the new player so they can display
    // the high scores on the join screen. This emits only to the
    // connecting socket.
    socket.emit('soloLeaderboardUpdate', { leaderboard: state.soloLeaderboard });
  });

  // Allow clients to query whether a lobby already exists. This lets
  // the UI display "Create Lobby" instead of "Join Lobby" when the
  // server has no connected players. We send back a boolean
  // indicating if there are currently any players.
  socket.on('requestLobbyStatus', () => {
    const hasLobby = state.players.length > 0;
    socket.emit('lobbyStatus', { hasLobby });
  });

  // The host initiates the spin to select a random mini game. This
  // event instructs all clients to start a spinner animation. Once
  // the spinner has ended on the client side it will emit
  // 'gameSelected' back to the server to confirm. This helps keep
  // client and server in sync.
  socket.on('startSpin', () => {
    if (socket.id !== state.host || state.gameRunning) return;
    // Choose from solo or multi games based on number of players. If fewer
    // than 2 players are present, we don't include "infected".
    const gameList = state.players.length < 2 ? soloGames : miniGames;
    const game = gameList[Math.floor(Math.random() * gameList.length)];
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
    // Determine if this round is solo mode
    state.soloMode = state.players.length < 2;
    // Record the start time for scoring; used for solo leaderboard
    state.startTime = Date.now();
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
    // If this was a solo round, compute a score and update the solo leaderboard
    const elapsed = Date.now() - (state.startTime || Date.now());
    if (state.soloMode && winners && Array.isArray(winners) && winners.length === 1) {
      const winnerId = winners[0];
      const player = state.players.find(p => p.id === winnerId);
      if (player) {
        let score;
        // For maze runs we reward shorter times with higher scores by subtracting from a maximum
        if (state.currentGame === 'mazeRunner') {
          const timeSec = elapsed / 1000;
          const maxSec = state.maxMazeTime / 1000;
          score = Math.max(0, maxSec - timeSec);
        } else {
          // For other games, we simply measure survival time
          score = elapsed / 1000;
        }
        // Round to integer seconds for readability
        score = Math.round(score);
        // Update solo leaderboard; keep highest score per player
        const existing = state.soloLeaderboard.find(entry => entry.name === player.name);
        if (existing) {
          if (score > existing.score) existing.score = score;
        } else {
          state.soloLeaderboard.push({ name: player.name, score });
        }
        // Sort descending and keep top 10
        state.soloLeaderboard.sort((a, b) => b.score - a.score);
        state.soloLeaderboard = state.soloLeaderboard.slice(0, 10);
        // Broadcast updated solo leaderboard to all clients
        io.emit('soloLeaderboardUpdate', { leaderboard: state.soloLeaderboard });
      }
    }
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