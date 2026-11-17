import { WEB_READY_TIMEOUT_MS } from '../notification/documentLoad.ts';

export type StartupStage = 'documentStarted' | 'documentLoaded' | 'webAlive' | 'webReady' | 'mapMounted' | 'mapReady';

export interface StartupDeadline {
  name: string;
  from: 'loadStarted' | StartupStage;
  until: StartupStage;
  ms: number;
  when: 'always' | 'holdingTap';
}

export interface StartupPolicy {
  splashHideOn: 'loadEnded' | 'webReady' | 'mapReady';
  deadlines: readonly StartupDeadline[];
}

export interface StartupState {
  epoch: number;
  documentId: string | null;
  started: boolean;
  loaded: boolean;
  alive: boolean;
  webReady: boolean;
  mapSessionId: string | null;
  mapReady: boolean;
  failed: boolean;
  splashHidden: boolean;
  timers: readonly string[];
}

export type StartupEvent =
  | { type: 'loadStarted' }
  | { type: 'documentStarted'; documentId?: string }
  | { type: 'loadEnded'; inApp: boolean; holdingTap: boolean }
  | { type: 'webAlive' }
  | { type: 'webReady'; documentId?: string }
  | { type: 'mapMounted'; sessionId: string }
  | { type: 'mapReady'; sessionId: string }
  | { type: 'loadFailed' }
  | { type: 'timerFired'; name: string; epoch: number; holdingTap: boolean };

export type StartupEffect =
  | { type: 'hideSplash' }
  | { type: 'showLoadFailed' }
  | { type: 'resetNativeMap' }
  | { type: 'startTimer'; name: string; epoch: number; ms: number }
  | { type: 'cancelTimer'; name: string }
  | { type: 'stage'; stage: StartupStage; epoch: number };

export interface StartupStep {
  state: StartupState;
  effects: readonly StartupEffect[];
}

export const CURRENT_STARTUP_POLICY: StartupPolicy = {
  splashHideOn: 'loadEnded',
  deadlines: [
    { name: 'webAlive', from: 'documentLoaded', until: 'webAlive', ms: WEB_READY_TIMEOUT_MS, when: 'holdingTap' },
  ],
};

export function initialStartup(): StartupState {
  return {
    epoch: 0,
    documentId: null,
    started: false,
    loaded: false,
    alive: false,
    webReady: false,
    mapSessionId: null,
    mapReady: false,
    failed: false,
    splashHidden: false,
    timers: [],
  };
}

function reached(state: StartupState, stage: StartupStage): boolean {
  if (stage === 'documentStarted') return state.started;
  if (stage === 'documentLoaded') return state.loaded;
  if (stage === 'webAlive') return state.alive;
  if (stage === 'webReady') return state.webReady;
  if (stage === 'mapMounted') return state.mapSessionId !== null;
  return state.mapReady;
}

function cancelAll(state: StartupState): StartupEffect[] {
  return state.timers.map((name) => ({ type: 'cancelTimer', name }));
}

function settle(
  policy: StartupPolicy,
  state: StartupState,
  entered: 'loadStarted' | StartupStage,
  holdingTap: boolean,
): StartupStep {
  const effects: StartupEffect[] = [];
  if (entered !== 'loadStarted') effects.push({ type: 'stage', stage: entered, epoch: state.epoch });
  let timers = [...state.timers];
  for (const deadline of policy.deadlines) {
    if (timers.includes(deadline.name) && reached(state, deadline.until)) {
      timers = timers.filter((name) => name !== deadline.name);
      effects.push({ type: 'cancelTimer', name: deadline.name });
    }
  }
  for (const deadline of policy.deadlines) {
    if (deadline.from !== entered || reached(state, deadline.until)) continue;
    if (deadline.when === 'holdingTap' && !holdingTap) continue;
    if (timers.includes(deadline.name)) effects.push({ type: 'cancelTimer', name: deadline.name });
    else timers.push(deadline.name);
    effects.push({ type: 'startTimer', name: deadline.name, epoch: state.epoch, ms: deadline.ms });
  }
  let splashHidden = state.splashHidden;
  const release =
    (policy.splashHideOn === 'webReady' && entered === 'webReady') ||
    (policy.splashHideOn === 'mapReady' && entered === 'mapReady');
  if (release && !splashHidden) {
    splashHidden = true;
    effects.push({ type: 'hideSplash' });
  }
  return { state: { ...state, timers, splashHidden }, effects };
}

export function startupStep(policy: StartupPolicy, state: StartupState, event: StartupEvent): StartupStep {
  switch (event.type) {
    case 'loadStarted': {
      const next: StartupState = {
        ...initialStartup(),
        epoch: state.epoch + 1,
        splashHidden: state.splashHidden,
      };
      const step = settle(policy, next, 'loadStarted', false);
      return { state: step.state, effects: [...cancelAll(state), { type: 'resetNativeMap' }, ...step.effects] };
    }
    case 'loadFailed': {
      const next: StartupState = {
        ...initialStartup(),
        epoch: state.epoch + 1,
        failed: true,
        splashHidden: true,
      };
      return {
        state: next,
        effects: [...cancelAll(state), { type: 'resetNativeMap' }, { type: 'showLoadFailed' }, { type: 'hideSplash' }],
      };
    }
    case 'documentStarted': {
      if (state.failed) return { state, effects: [] };
      const documentId = event.documentId ?? state.documentId;
      if (state.started) return { state: { ...state, documentId }, effects: [] };
      return settle(policy, { ...state, started: true, documentId }, 'documentStarted', false);
    }
    case 'loadEnded': {
      const effects: StartupEffect[] = [];
      let next = state;
      if (policy.splashHideOn === 'loadEnded' && !state.splashHidden) {
        next = { ...next, splashHidden: true };
        effects.push({ type: 'hideSplash' });
      }
      if (next.failed) return { state: next, effects };
      next = { ...next, started: true };
      if (!event.inApp) return { state: next, effects };
      const first = !next.loaded;
      const step = settle(policy, { ...next, loaded: true }, 'documentLoaded', event.holdingTap);
      const stepEffects = first ? step.effects : step.effects.filter((effect) => effect.type !== 'stage');
      return { state: step.state, effects: [...effects, ...stepEffects] };
    }
    case 'webAlive': {
      if (state.failed || !state.started || state.alive) return { state, effects: [] };
      return settle(policy, { ...state, alive: true }, 'webAlive', false);
    }
    case 'webReady': {
      if (state.failed || !state.started || state.webReady) return { state, effects: [] };
      if (event.documentId !== undefined && event.documentId !== state.documentId) return { state, effects: [] };
      const alive = settle(policy, { ...state, alive: true }, 'webAlive', false);
      const ready = settle(policy, { ...alive.state, webReady: true }, 'webReady', false);
      return { state: ready.state, effects: [...(state.alive ? [] : alive.effects), ...ready.effects] };
    }
    case 'mapMounted': {
      if (state.failed || !state.started) return { state, effects: [] };
      return settle(policy, { ...state, mapSessionId: event.sessionId, mapReady: false }, 'mapMounted', false);
    }
    case 'mapReady': {
      if (state.failed || state.mapSessionId !== event.sessionId || state.mapReady) return { state, effects: [] };
      return settle(policy, { ...state, mapReady: true }, 'mapReady', false);
    }
    case 'timerFired': {
      if (event.epoch !== state.epoch || !state.timers.includes(event.name)) return { state, effects: [] };
      const deadline = policy.deadlines.find(({ name }) => name === event.name);
      const timers = state.timers.filter((name) => name !== event.name);
      if (deadline === undefined || reached(state, deadline.until)) return { state: { ...state, timers }, effects: [] };
      if (deadline.when === 'holdingTap' && !event.holdingTap) return { state: { ...state, timers }, effects: [] };
      return startupStep(policy, { ...state, timers }, { type: 'loadFailed' });
    }
  }
}
