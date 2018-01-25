import * as WebSocket from 'ws';
import * as uuidv4 from 'uuid/v4';
import { EventEmitter } from 'events';

enum MessageType {
  MATCHED = 'matched',
  SDP = 'sdp',
  ICE = 'ice',
  PEER_LEFT = 'peer-left'
}

type MatchMessage = {
  type: MessageType.MATCHED
  match: string
  offer: boolean
}

type SDPMessage = {
  type: MessageType.SDP
  sdp: string
}

type ICEMessage = {
  type: MessageType.ICE
  candidate: string,
  label: number,
  id: string
}

type PeerLeft = {
  type: MessageType.PEER_LEFT
}

type ClientMessage
  = MatchMessage
  | SDPMessage
  | ICEMessage
  | PeerLeft

type Session = {
  id: string
  ws: WebSocket
  peer?: string
}

export default class Roulette {

  private sessions : Map<string, Session>;
  private unmatched : Array<string>;

  constructor() {
    this.sessions = new Map();
    this.unmatched = [];
  } 

  register(ws: WebSocket) {
    const id = uuidv4();
    const session = { id, ws };
    
    this.sessions.set(id, session);
    this.tryMatch(session);
    
    ws.on('close', () => this.unregister(id));
    ws.on('error', () => this.unregister(id));
    ws.on('message', (data: WebSocket.Data) => this.handleMessage(id, data.toString()));
  }

  private handleMessage(id: string, data: string) {
    try {
      const message = JSON.parse(data) as ClientMessage;
      const session = this.sessions.get(id);
      if(!session) { return console.error(`Can't find session for ${id}`); }
      const peer = this.sessions.get(session.peer);
      if(!peer) { return console.error(`Can't find session for peer of ${id}`); }
      switch (message.type) {
        case MessageType.SDP:
        case MessageType.ICE:
          this.send(peer, message);
          break;
        default:
          console.error(`Unexpected message from ${id}: ${data}`);
          break;
        }
    } catch(err) {
      console.error(`Unexpected message from ${id}: ${data}`);
    }
  }

  private tryMatch(session: Session) {
    if (this.unmatched.length > 0) {
      const match = this.unmatched.shift();
      const other = this.sessions.get(match);
      if (other) {
        session.peer = match;
        other.peer   = session.id;
        this.send(session, {type: MessageType.MATCHED, match: other.id, offer: true});
        this.send(other, {type: MessageType.MATCHED, match: session.id, offer: false});
      }
    } else {
      this.unmatched.push(session.id);
    }
  }

  private unregister(id: string) {
    const session = this.sessions.get(id);
    if(session && session.peer) {
      const peer = this.sessions.get(session.peer);
      if(peer) this.send(peer, { type: MessageType.PEER_LEFT })
    }
    this.unmatched = this.unmatched.filter(other => id !== other);
    this.sessions.delete(id);
  }

  private send(session: Session, payload: ClientMessage) {
    try {
      if(session.ws.readyState === WebSocket.OPEN) {
        session.ws.send(JSON.stringify(payload));
      }
    } catch(err) {
      console.error(`Error sending to ${session.id}`);
    }
  }

}