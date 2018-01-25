import { Server } from 'ws';
import { createServer } from 'http';
import Roulette from './roulette';

const wss = new Server({ port: 8000 });
const mm = new Roulette();

wss.on('connection', ws => {
  mm.register(ws);
});