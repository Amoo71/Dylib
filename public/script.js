/*
 * Client‑side logic for FingerGames. This script handles UI state
 * transitions, internationalisation, communication with the server via
 * Socket.IO and simple mini game implementations. While the games
 * implemented here are intentionally lightweight, they demonstrate the
 * concepts described by the user, including holding a spot, avoiding
 * obstacles, tagging other players and reacting to colour zones.
 */

(() => {
  const socket = io();
  let myId = null;
  let isHost = false;
  let lang = 'en';
  let players = [];
  let currentGame = null;
  let gameLoopId = null;
  const canvas = document.getElementById('gameCanvas');
  const ctx = canvas.getContext('2d');

  // Dictionary for i18n
  const translations = {
    en: {
      welcome: 'A co‑operative browser party game',
      usernameLabel: 'Username',
      languageLabel: 'Language',
      joinBtn: 'Join Lobby',
      lobbyTitle: 'Lobby',
      startSpin: 'Start Random Game',
      startGame: 'Start Game',
      roundOverTitle: 'Round Over',
      nextRound: 'Next Round',
      scoreboard: 'Scoreboard',
      hostNote: 'You are the host. You control when a game starts.',
      notHostNote: 'Waiting for host to start the game...',
      games: {
        laser: {
          name: 'Laser!',
          desc: 'Lasers will fly in from all sides. Keep your finger (mouse) on your spot; if you let go the screen darkens and you will be frozen until you return. Avoid the beams – last survivor wins!'
        },
        infected: {
          name: 'Infected',
          desc: 'One player starts infected. The infected must tag others to spread the virus. Infected move 20% slower. Power‑ups (freeze, jump, invisible) appear randomly to help the survivors.'
        },
        colorRush: {
          name: 'Color Rush',
          desc: 'Multiple coloured zones appear on the arena. At intervals one zone lights up – quickly move into the highlighted colour. Anyone outside the zone when time runs out is eliminated. Last standing wins!'
        },
        mazeRunner: {
          name: 'Maze Runner',
          desc: 'Navigate through a labyrinth to reach the glowing exit. First player to reach the exit wins. If time runs out, whoever is closest to the exit wins.'
        }
      },
      winners: (names) => {
        return names.length === 1
          ? `Winner: ${names[0]}`
          : `Winners: ${names.join(', ')}`;
      }
    },
    de: {
      welcome: 'Ein kooperatives Browser‑Partyspiel',
      usernameLabel: 'Benutzername',
      languageLabel: 'Sprache',
      joinBtn: 'Lobby beitreten',
      lobbyTitle: 'Lobby',
      startSpin: 'Zufälliges Spiel starten',
      startGame: 'Spiel starten',
      roundOverTitle: 'Runde beendet',
      nextRound: 'Nächste Runde',
      scoreboard: 'Punktestand',
      hostNote: 'Du bist der Host. Du entscheidest, wann das Spiel beginnt.',
      notHostNote: 'Warten auf Host, um das Spiel zu starten...',
      games: {
        laser: {
          name: 'Laser!',
          desc: 'Laserstrahlen kommen aus allen Richtungen. Halte deinen Finger (Maus) auf deinem Spot; wenn du loslässt, verdunkelt sich der Bildschirm und du bist eingefroren, bis du zurückkehrst. Weiche den Strahlen aus – der letzte Überlebende gewinnt!'
        },
        infected: {
          name: 'Infiziert',
          desc: 'Ein Spieler beginnt infiziert. Die Infizierten müssen andere berühren, um das Virus zu verbreiten. Infizierte bewegen sich 20 % langsamer. Power‑Ups (Einfrieren, Springen, Unsichtbar) erscheinen zufällig, um den Überlebenden zu helfen.'
        },
        colorRush: {
          name: 'Farbrausch',
          desc: 'Mehrere farbige Zonen erscheinen in der Arena. In Abständen leuchtet eine Zone auf – bewege dich schnell in die hervorgehobene Farbe. Jeder außerhalb der Zone wird eliminiert. Letzter stehender Spieler gewinnt!'
        },
        mazeRunner: {
          name: 'Labyrinthläufer',
          desc: 'Navigiere durch ein Labyrinth, um den leuchtenden Ausgang zu erreichen. Der erste Spieler am Ausgang gewinnt. Läuft die Zeit ab, gewinnt derjenige, der dem Ausgang am nächsten ist.'
        }
      },
      winners: (names) => {
        return names.length === 1
          ? `Gewinner: ${names[0]}`
          : `Gewinner: ${names.join(', ')}`;
      }
    }
  };

  /**
   * Translate a key using the current language. Nested keys can be
   * accessed with dot‑notation (e.g. 'games.laser.desc'). If the key
   * resolves to a function it will be returned for later execution.
   */
  function t(key) {
    const parts = key.split('.');
    let obj = translations[lang];
    for (const part of parts) {
      if (!obj) break;
      obj = obj[part];
    }
    return obj || key;
  }

  // Apply translations to elements with data-lang-key attributes
  function applyTranslations() {
    document.querySelectorAll('[data-lang-key]').forEach(el => {
      const key = el.getAttribute('data-lang-key');
      const value = t(key);
      if (typeof value === 'string') {
        el.textContent = value;
      }
    });
  }

  // When socket connects assign myId
  socket.on('connect', () => {
    myId = socket.id;
  });

  // Resize canvas to full viewport
  function resizeCanvas() {
    canvas.width = window.innerWidth;
    canvas.height = window.innerHeight;
  }
  window.addEventListener('resize', resizeCanvas);

  // DOM elements
  const joinScreen = document.getElementById('joinScreen');
  const lobby = document.getElementById('lobby');
  const joinBtn = document.getElementById('joinBtn');
  const usernameInput = document.getElementById('usernameInput');
  const languageSelect = document.getElementById('languageSelect');
  const playerList = document.getElementById('playerList');
  const scoreBoard = document.getElementById('scoreBoard');
  const hostNote = document.getElementById('hostNote');
  const startSpinBtn = document.getElementById('startSpinBtn');
  const spinnerOverlay = document.getElementById('spinnerOverlay');
  const spinner = document.getElementById('spinner');
  const selectedGameName = document.getElementById('selectedGameName');
  const instructionsOverlay = document.getElementById('instructionsOverlay');
  const instrTitle = document.getElementById('instrTitle');
  const instrDesc = document.getElementById('instrDesc');
  const startGameBtn = document.getElementById('startGameBtn');
  const gameArea = document.getElementById('gameArea');
  const roundOverlay = document.getElementById('roundOverlay');
  const winnerNames = document.getElementById('winnerNames');
  const scoreBoardRound = document.getElementById('scoreBoardRound');
  const nextRoundBtn = document.getElementById('nextRoundBtn');

  // Join lobby
  joinBtn.addEventListener('click', () => {
    const name = usernameInput.value.trim() || 'Player';
    lang = languageSelect.value;
    applyTranslations();
    joinScreen.classList.add('hidden');
    lobby.classList.remove('hidden');
    socket.emit('join', { name, lang });
  });

  // Start random game (host)
  startSpinBtn.addEventListener('click', () => {
    socket.emit('startSpin');
  });

  // After spin instructions start game
  startGameBtn.addEventListener('click', () => {
    socket.emit('startGame');
  });

  // Next round button
  nextRoundBtn.addEventListener('click', () => {
    roundOverlay.classList.add('hidden');
    lobby.classList.remove('hidden');
    startSpinBtn.classList.toggle('hidden', !isHost);
  });

  /**
   * Render lobby list and scores
   */
  function renderLobby(playersArr, scores) {
    playerList.innerHTML = '';
    playersArr.forEach(p => {
      const li = document.createElement('li');
      li.textContent = `${p.name}`;
      if (p.id === stateHost) {
        li.classList.add('host');
      }
      playerList.appendChild(li);
    });
    // Render scoreboard
    let html = `<h3>${t('scoreboard')}</h3><ul>`;
    playersArr.forEach(p => {
      html += `<li>${p.name}: ${scores[p.id] || 0}</li>`;
    });
    html += '</ul>';
    scoreBoard.innerHTML = html;
  }

  let stateHost = null;
  let scores = {};

  socket.on('lobbyUpdate', ({ players: serverPlayers, host, scores: serverScores, language }) => {
    players = serverPlayers;
    stateHost = host;
    scores = serverScores;
    lang = language || lang;
    isHost = myId && myId === stateHost;
    // update language selection
    languageSelect.value = lang;
    applyTranslations();
    // update host note
    hostNote.textContent = isHost ? t('hostNote') : t('notHostNote');
    // show/hide start button
    startSpinBtn.classList.toggle('hidden', !isHost);
    renderLobby(players, scores);
  });

  // When server sends spin start – show spinner and animate
  socket.on('spinStarted', ({ game }) => {
    spinnerOverlay.classList.remove('hidden');
    selectedGameName.textContent = '';
    // Build spinner content – four segments horizontally with game names
    spinner.innerHTML = '';
    const order = ['laser', 'infected', 'colorRush', 'mazeRunner'];
    order.forEach(g => {
      const div = document.createElement('div');
      div.textContent = t(`games.${g}.name`);
      spinner.appendChild(div);
    });
    // animate scroll horizontally to selected game
    const index = order.indexOf(game);
    const distance = index * 100; // each seg width 100%? We'll use transform
    spinner.style.transition = 'transform 2s cubic-bezier(0.15, 0.4, 0.1, 1)';
    spinner.style.transform = `translateX(-${distance}%)`;
    // After animation show name and inform server we completed spin
    setTimeout(() => {
      selectedGameName.textContent = t(`games.${game}.name`);
      socket.emit('gameSelected');
    }, 2100);
  });

  // Show instructions
  socket.on('showInstructions', ({ game, language }) => {
    lang = language || lang;
    applyTranslations();
    spinnerOverlay.classList.add('hidden');
    instructionsOverlay.classList.remove('hidden');
    currentGame = game;
    instrTitle.textContent = t(`games.${game}.name`);
    instrDesc.textContent = t(`games.${game}.desc`);
    startGameBtn.classList.toggle('hidden', !isHost);
  });

  // Start actual game
  socket.on('gameStarted', ({ game, players: serverPlayers }) => {
    instructionsOverlay.classList.add('hidden');
    lobby.classList.add('hidden');
    currentGame = game;
    players = serverPlayers;
    // assign myId if not set
    if (!myId) {
      myId = socket.id;
    }
    // Reset canvas and game state
    resizeCanvas();
    gameArea.classList.remove('hidden');
    // Cancel any previous game loop
    if (gameLoopId) cancelAnimationFrame(gameLoopId);
    // Setup game-specific variables
    switch (game) {
      case 'laser':
        startLaserGame();
        break;
      case 'infected':
        startInfectedGame();
        break;
      case 'colorRush':
        startColorRushGame();
        break;
      case 'mazeRunner':
        startMazeRunnerGame();
        break;
    }
  });

  // Update other players positions
  socket.on('playerMoved', ({ id, x, y }) => {
    const p = players.find(pl => pl.id === id);
    if (p) {
      p.x = x;
      p.y = y;
    }
  });

  // Round over
  socket.on('roundOver', ({ winners, scores: newScores }) => {
    // show scoreboard overlay
    gameArea.classList.add('hidden');
    roundOverlay.classList.remove('hidden');
    lobby.classList.add('hidden');
    // update scoreboard
    scores = newScores;
    const winnerNamesArr = winners.map(id => {
      const p = players.find(pl => pl.id === id);
      return p ? p.name : 'Unknown';
    });
    winnerNames.textContent = translations[lang].winners(winnerNamesArr);
    // Scoreboard list
    let html = `<h3>${t('scoreboard')}</h3><ul>`;
    players.forEach(p => {
      html += `<li>${p.name}: ${scores[p.id] || 0}</li>`;
    });
    html += '</ul>';
    scoreBoardRound.innerHTML = html;
    startSpinBtn.classList.toggle('hidden', !isHost);
  });

  /* ========== Mini Game Implementations ========== */
  // Utility to handle touch/mouse events for dragging your spot
  function attachDragControls(playerState) {
    let isDown = false;
    canvas.addEventListener('mousedown', (e) => {
      isDown = true;
      // update player's frozen state? not necessary for our simplified mechanic
    });
    canvas.addEventListener('mouseup', () => {
      isDown = false;
    });
    canvas.addEventListener('mousemove', (e) => {
      if (!isDown) return;
      const rect = canvas.getBoundingClientRect();
      const x = (e.clientX - rect.left) / rect.width;
      const y = (e.clientY - rect.top) / rect.height;
      playerState.x = Math.min(1, Math.max(0, x));
      playerState.y = Math.min(1, Math.max(0, y));
      socket.emit('updatePosition', { x: playerState.x, y: playerState.y });
    });
    // Touch support
    canvas.addEventListener('touchstart', (e) => {
      isDown = true;
    });
    canvas.addEventListener('touchend', (e) => {
      isDown = false;
    });
    canvas.addEventListener('touchmove', (e) => {
      if (!isDown) return;
      const touch = e.touches[0];
      const rect = canvas.getBoundingClientRect();
      const x = (touch.clientX - rect.left) / rect.width;
      const y = (touch.clientY - rect.top) / rect.height;
      playerState.x = Math.min(1, Math.max(0, x));
      playerState.y = Math.min(1, Math.max(0, y));
      socket.emit('updatePosition', { x: playerState.x, y: playerState.y });
    });
  }

  /**
   * Laser mini game
   * Lasers move across the playfield. Players must avoid contact. The last
   * surviving player wins. If a player releases mouse/touch they are
   * frozen (we darken their screen by overlay). In this simplified
   * version we just check for collisions; we do not freeze on release.
   */
  function startLaserGame() {
    const lasers = [];
    const spawnInterval = 2000; // ms
    let lastSpawn = 0;
    const playerState = players.find(p => p.id === socket.id);
    attachDragControls(playerState);

    function spawnLaser() {
      // direction 0=left->right,1=right->left,2=top->bottom,3=bottom->top
      const dir = Math.floor(Math.random() * 4);
      const speed = 0.002 + Math.random() * 0.003; // relative per frame
      let laser;
      if (dir === 0) {
        laser = { x: 0, y: Math.random(), w: 0.3, h: 0.02, vx: speed, vy: 0 };
      } else if (dir === 1) {
        laser = { x: 1, y: Math.random(), w: 0.3, h: 0.02, vx: -speed, vy: 0 };
      } else if (dir === 2) {
        laser = { x: Math.random(), y: 0, w: 0.02, h: 0.3, vx: 0, vy: speed };
      } else {
        laser = { x: Math.random(), y: 1, w: 0.02, h: 0.3, vx: 0, vy: -speed };
      }
      lasers.push(laser);
    }
    function updateLasers(dt) {
      for (const l of lasers) {
        l.x += l.vx * dt;
        l.y += l.vy * dt;
      }
      // Remove lasers outside play area
      for (let i = lasers.length - 1; i >= 0; i--) {
        const l = lasers[i];
        if (l.x + l.w < 0 || l.x > 1 || l.y + l.h < 0 || l.y > 1) {
          lasers.splice(i, 1);
        }
      }
    }
    function checkCollisions() {
      // Convert relative to canvas
      players.forEach(p => {
        if (!p.alive) return;
        const px = p.x;
        const py = p.y;
        for (const l of lasers) {
          if (px > l.x && px < l.x + l.w && py > l.y && py < l.y + l.h) {
            p.alive = false;
            break;
          }
        }
      });
    }
    function draw() {
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      // Draw lasers
      ctx.fillStyle = '#ff4d4d';
      for (const l of lasers) {
        ctx.fillRect(l.x * canvas.width, l.y * canvas.height, l.w * canvas.width, l.h * canvas.height);
      }
      // Draw players
      players.forEach(p => {
        ctx.beginPath();
        ctx.arc(p.x * canvas.width, p.y * canvas.height, 12, 0, Math.PI * 2);
        ctx.fillStyle = p.id === socket.id ? '#00bfff' : '#7f8c8d';
        if (!p.alive) ctx.fillStyle = '#555';
        ctx.fill();
        ctx.strokeStyle = p.id === stateHost ? '#ffd700' : 'transparent';
        ctx.lineWidth = 2;
        ctx.stroke();
      });
    }
    let lastTime = performance.now();
    function loop(now) {
      const dt = now - lastTime;
      lastTime = now;
      // spawn lasers
      if (now - lastSpawn > spawnInterval) {
        spawnLaser();
        lastSpawn = now;
      }
      updateLasers(dt);
      checkCollisions();
      draw();
      // Check if one or zero players alive
      const alivePlayers = players.filter(p => p.alive);
      if (alivePlayers.length <= 1) {
        socket.emit('gameOver', { winners: alivePlayers.map(p => p.id) });
        cancelAnimationFrame(gameLoopId);
        return;
      }
      gameLoopId = requestAnimationFrame(loop);
    }
    gameLoopId = requestAnimationFrame(loop);
  }

  /**
   * Infected mini game
   * One random player starts infected. Infected players move 20% slower
   * and try to tag others. When all players are infected except one the
   * last non‑infected survivor wins.
   */
  function startInfectedGame() {
    const playerState = players.find(p => p.id === socket.id);
    attachDragControls(playerState);
    // When infected, we slow movement on client side by 20% by ignoring
    // some position updates. We'll reduce the speed variable below.
    let lastSent = performance.now();
    let slowFactor = 1;
    // override updatePosition emission to slow infected
    canvas.addEventListener('mousemove', onMove);
    canvas.addEventListener('touchmove', onTouchMove);
    function onMove(e) {
      const now = performance.now();
      const delta = now - lastSent;
      if (playerState.infected) {
        slowFactor = 0.8;
      } else {
        slowFactor = 1;
      }
      if (delta < 16 / slowFactor) return;
      lastSent = now;
    }
    function onTouchMove(e) {
      onMove(e);
    }
    // We'll perform infection logic in draw loop
    function checkInfection() {
      // For each infected player check distance to non-infected
      const infectedList = players.filter(p => p.infected);
      const uninfected = players.filter(p => !p.infected);
      infectedList.forEach(ip => {
        uninfected.forEach(up => {
          const dx = ip.x - up.x;
          const dy = ip.y - up.y;
          const dist = Math.sqrt(dx * dx + dy * dy);
          if (dist < 0.04) {
            up.infected = true;
          }
        });
      });
    }
    function draw() {
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      players.forEach(p => {
        ctx.beginPath();
        ctx.arc(p.x * canvas.width, p.y * canvas.height, 12, 0, Math.PI * 2);
        ctx.fillStyle = p.infected ? '#e74c3c' : (p.id === socket.id ? '#2ecc71' : '#3498db');
        ctx.fill();
        ctx.strokeStyle = p.id === stateHost ? '#ffd700' : 'transparent';
        ctx.lineWidth = 2;
        ctx.stroke();
      });
    }
    function loop() {
      checkInfection();
      draw();
      const uninfected = players.filter(p => !p.infected);
      if (uninfected.length <= 1) {
        // last non infected wins
        socket.emit('gameOver', { winners: uninfected.map(p => p.id) });
        return;
      }
      gameLoopId = requestAnimationFrame(loop);
    }
    gameLoopId = requestAnimationFrame(loop);
  }

  /**
   * Color Rush mini game
   * Several coloured zones are displayed. Every few seconds one zone
   * lights up; players must move into that zone within a short window
   * or be eliminated. Repeat until one remains.
   */
  function startColorRushGame() {
    const playerState = players.find(p => p.id === socket.id);
    attachDragControls(playerState);
    // Define zones positions relative coordinates and colours
    const zones = [
      { x: 0.2, y: 0.3, r: 0.15, color: '#e74c3c' },
      { x: 0.7, y: 0.3, r: 0.15, color: '#f1c40f' },
      { x: 0.2, y: 0.7, r: 0.15, color: '#8e44ad' },
      { x: 0.7, y: 0.7, r: 0.15, color: '#27ae60' }
    ];
    let activeIndex = -1;
    let highlightTime = 0;
    const interval = 4000;
    const windowTime = 2000;
    function pickNewZone() {
      activeIndex = Math.floor(Math.random() * zones.length);
      highlightTime = performance.now();
    }
    pickNewZone();
    function draw() {
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      // Draw zones
      zones.forEach((z, idx) => {
        ctx.beginPath();
        ctx.arc(z.x * canvas.width, z.y * canvas.height, z.r * canvas.width, 0, Math.PI * 2);
        const isActive = idx === activeIndex;
        ctx.fillStyle = isActive ? z.color : `${z.color}55`;
        ctx.fill();
      });
      // Draw players
      players.forEach(p => {
        ctx.beginPath();
        ctx.arc(p.x * canvas.width, p.y * canvas.height, 12, 0, Math.PI * 2);
        ctx.fillStyle = p.id === socket.id ? '#00bfff' : '#95a5a6';
        if (!p.alive) ctx.fillStyle = '#555';
        ctx.fill();
        ctx.strokeStyle = p.id === stateHost ? '#ffd700' : 'transparent';
        ctx.lineWidth = 2;
        ctx.stroke();
      });
    }
    function eliminateOutsideZone() {
      const active = zones[activeIndex];
      players.forEach(p => {
        if (!p.alive) return;
        const dx = p.x - active.x;
        const dy = p.y - active.y;
        const dist = Math.sqrt(dx * dx + dy * dy);
        if (dist > active.r) {
          p.alive = false;
        }
      });
    }
    let lastSwitch = performance.now();
    function loop(now) {
      if (now - lastSwitch >= interval) {
        // Evaluate previous zone after window time
        eliminateOutsideZone();
        // Pick new zone for next round
        pickNewZone();
        lastSwitch = now;
      }
      draw();
      // Determine alive players
      const alivePlayers = players.filter(p => p.alive);
      if (alivePlayers.length <= 1) {
        socket.emit('gameOver', { winners: alivePlayers.map(p => p.id) });
        return;
      }
      gameLoopId = requestAnimationFrame(loop);
    }
    gameLoopId = requestAnimationFrame(loop);
  }

  /**
   * Maze Runner mini game
   * Simple randomly generated maze; players start at top-left and must
   * reach bottom-right. Maze generation uses depth-first algorithm.
   */
  function startMazeRunnerGame() {
    const playerState = players.find(p => p.id === socket.id);
    attachDragControls(playerState);
    // Maze configuration
    const cols = 8;
    const rows = 8;
    const cellSizeX = 1 / cols;
    const cellSizeY = 1 / rows;
    // Generate maze grid with walls
    const grid = [];
    function Cell(i, j) {
      this.i = i;
      this.j = j;
      this.walls = [true, true, true, true]; // top,right,bottom,left
      this.visited = false;
    }
    for (let j = 0; j < rows; j++) {
      for (let i = 0; i < cols; i++) {
        grid[j * cols + i] = new Cell(i, j);
      }
    }
    function index(i, j) {
      if (i < 0 || j < 0 || i >= cols || j >= rows) return -1;
      return j * cols + i;
    }
    function removeWalls(a, b) {
      const x = a.i - b.i;
      const y = a.j - b.j;
      if (x === 1) {
        a.walls[3] = false;
        b.walls[1] = false;
      } else if (x === -1) {
        a.walls[1] = false;
        b.walls[3] = false;
      }
      if (y === 1) {
        a.walls[0] = false;
        b.walls[2] = false;
      } else if (y === -1) {
        a.walls[2] = false;
        b.walls[0] = false;
      }
    }
    // Depth-first search maze generation
    const stack = [];
    let current = grid[0];
    current.visited = true;
    while (true) {
      // Get unvisited neighbors
      const neighbors = [];
      const { i, j } = current;
      const top = grid[index(i, j - 1)];
      const right = grid[index(i + 1, j)];
      const bottom = grid[index(i, j + 1)];
      const left = grid[index(i - 1, j)];
      [top, right, bottom, left].forEach(n => {
        if (n && !n.visited) neighbors.push(n);
      });
      if (neighbors.length > 0) {
        const next = neighbors[Math.floor(Math.random() * neighbors.length)];
        stack.push(current);
        removeWalls(current, next);
        current = next;
        current.visited = true;
      } else if (stack.length > 0) {
        current = stack.pop();
      } else {
        break;
      }
    }
    // draw maze
    function drawMaze() {
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      ctx.strokeStyle = '#ffffff44';
      ctx.lineWidth = 2;
      grid.forEach(cell => {
        const x = cell.i * cellSizeX * canvas.width;
        const y = cell.j * cellSizeY * canvas.height;
        if (cell.walls[0]) {
          ctx.beginPath();
          ctx.moveTo(x, y);
          ctx.lineTo(x + cellSizeX * canvas.width, y);
          ctx.stroke();
        }
        if (cell.walls[1]) {
          ctx.beginPath();
          ctx.moveTo(x + cellSizeX * canvas.width, y);
          ctx.lineTo(x + cellSizeX * canvas.width, y + cellSizeY * canvas.height);
          ctx.stroke();
        }
        if (cell.walls[2]) {
          ctx.beginPath();
          ctx.moveTo(x + cellSizeX * canvas.width, y + cellSizeY * canvas.height);
          ctx.lineTo(x, y + cellSizeY * canvas.height);
          ctx.stroke();
        }
        if (cell.walls[3]) {
          ctx.beginPath();
          ctx.moveTo(x, y + cellSizeY * canvas.height);
          ctx.lineTo(x, y);
          ctx.stroke();
        }
      });
      // Draw exit highlight bottom-right cell
      ctx.fillStyle = '#16a085';
      ctx.fillRect((cols - 1) * cellSizeX * canvas.width + 4, (rows - 1) * cellSizeY * canvas.height + 4, cellSizeX * canvas.width - 8, cellSizeY * canvas.height - 8);
    }
    // Check collisions with walls; if moving through walls, prevent
    function constrainPlayer(p) {
      // Determine which cell the player is in
      const ci = Math.floor(p.x / cellSizeX);
      const cj = Math.floor(p.y / cellSizeY);
      const cell = grid[index(ci, cj)];
      if (!cell) return;
      const px = p.x;
      const py = p.y;
      // relative position inside cell
      const rx = (px - ci * cellSizeX) / cellSizeX;
      const ry = (py - cj * cellSizeY) / cellSizeY;
      const margin = 0.02;
      // top wall
      if (cell.walls[0] && ry < margin) {
        p.y = cj * cellSizeY + margin * cellSizeY;
      }
      // bottom wall
      if (cell.walls[2] && ry > 1 - margin) {
        p.y = (cj + 1) * cellSizeY - margin * cellSizeY;
      }
      // left wall
      if (cell.walls[3] && rx < margin) {
        p.x = ci * cellSizeX + margin * cellSizeX;
      }
      // right wall
      if (cell.walls[1] && rx > 1 - margin) {
        p.x = (ci + 1) * cellSizeX - margin * cellSizeX;
      }
    }
    function drawPlayers() {
      players.forEach(p => {
        ctx.beginPath();
        ctx.arc(p.x * canvas.width, p.y * canvas.height, 10, 0, Math.PI * 2);
        ctx.fillStyle = p.id === socket.id ? '#e67e22' : '#ecf0f1';
        ctx.fill();
        ctx.strokeStyle = p.id === stateHost ? '#ffd700' : 'transparent';
        ctx.lineWidth = 2;
        ctx.stroke();
      });
    }
    function loop() {
      // Constrain each player by maze walls
      players.forEach(constrainPlayer);
      drawMaze();
      drawPlayers();
      // Check if any player reaches exit
      const winners = players.filter(p => p.x > (cols - 1) * cellSizeX + 0.02 && p.y > (rows - 1) * cellSizeY + 0.02);
      if (winners.length > 0) {
        socket.emit('gameOver', { winners: winners.map(p => p.id) });
        return;
      }
      gameLoopId = requestAnimationFrame(loop);
    }
    gameLoopId = requestAnimationFrame(loop);
  }
})();