export type TunnelMode = 'BHTTP' | 'WS';

export type VpnStatusState =
  | 'Desconectado'
  | 'Conectando...'
  | 'Conectado'
  | 'Reconectando'
  | 'Error';

export interface VpnFormValues {
  mode: TunnelMode;
  host: string;
  port: string;
  user: string;
  pass: string;
  payload: string;
}

export interface FormErrors {
  host?: string;
  port?: string;
  user?: string;
  pass?: string;
  payload?: string;
}
