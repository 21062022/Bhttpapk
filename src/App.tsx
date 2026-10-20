/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import React, { useState, useRef, useEffect } from 'react';
import { Eye, EyeOff, ShieldAlert } from 'lucide-react';
import { TunnelMode, VpnStatusState, VpnFormValues, FormErrors } from './types/vpn';

export default function App() {
  const [mode, setMode] = useState<TunnelMode>('BHTTP');
  const [form, setForm] = useState<VpnFormValues>({
    mode: 'BHTTP',
    host: '',
    port: '',
    user: '',
    pass: '',
    payload: '',
  });

  const [errors, setErrors] = useState<FormErrors>({});
  const [status, setStatus] = useState<VpnStatusState>('Desconectado');
  const [statusDetail, setStatusDetail] = useState<string>('Desconectado');
  const [logs, setLogs] = useState<string[]>([]);
  const [showPass, setShowPass] = useState(false);
  const logEndRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    logEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [logs]);

  const addLog = (msg: string) => {
    const time = new Date().toLocaleTimeString('es-ES', { hour12: false });
    setLogs(prev => [...prev, `[${time}] ${msg}`]);
  };

  const validate = (): boolean => {
    const newErrors: FormErrors = {};

    const hostTrim = form.host.trim();
    if (!hostTrim) {
      newErrors.host = 'El servidor (IP o dominio) es obligatorio.';
    } else if (!/^([a-zA-Z0-9.-]+)$/.test(hostTrim)) {
      newErrors.host = 'Introduce una dirección IP o nombre de dominio válido.';
    }

    const portTrim = form.port.trim();
    if (!portTrim) {
      newErrors.port = 'El puerto es obligatorio.';
    } else {
      const portNum = parseInt(portTrim, 10);
      if (isNaN(portNum) || portNum < 1 || portNum > 65535) {
        newErrors.port = 'El puerto debe ser un número entre 1 y 65535.';
      }
    }

    if (!form.user.trim()) {
      newErrors.user = 'El usuario SSH es obligatorio.';
    }

    if (!form.pass) {
      newErrors.pass = 'La contraseña SSH es obligatoria.';
    }

    if (mode === 'WS' && !form.payload.trim()) {
      newErrors.payload = 'El payload no puede estar vacío en modo WS.';
    }

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const handleToggle = () => {
    if (status !== 'Desconectado' && status !== 'Error') {
      setStatus('Desconectado');
      setStatusDetail('Desconectado');
      addLog('Desconexión solicitada por el usuario.');
      return;
    }

    // Validación estricta: nunca conectar con datos vacíos o erróneos
    if (!validate()) {
      setStatus('Error');
      setStatusDetail('Datos de configuración incompletos o inválidos');
      addLog('Error de validación: por favor revisa los campos marcados en rojo.');
      return;
    }

    // Regla de oro: No fingir conexión en entorno web
    setStatus('Error');
    setStatusDetail('La conexión real solo funciona en el APK instalado');
    addLog(`Validación completada para ${form.host.trim()}:${form.port.trim()} (${mode}).`);
    addLog('AVISO: La conexión real requiere la API nativa de Android (VpnService + tun2socks C NDK).');
    addLog('Este navegador no tiene privilegios de red ni sockets crudos de sistema para montar una interfaz TUN real.');
    addLog('Para conectar la VPN real, compila e instala el proyecto Android incluido en /BhttpTunnel.');
  };

  const clearLogs = () => {
    setLogs([]);
  };

  return (
    <div className="min-h-screen bg-[#0B0F19] text-slate-100 flex items-center justify-center p-4 font-sans selection:bg-blue-600/30">
      <div className="w-full max-w-md bg-[#0F172A] border border-slate-800 rounded-2xl p-6 shadow-2xl space-y-4">
        
        {/* Encabezado */}
        <div className="text-center space-y-1">
          <h1 className="text-2xl font-black text-[#0A5CFF] tracking-tight">
            BHTTP Tunnel
          </h1>
          <div className="text-xs font-semibold">
            <span className="text-slate-400">Estado: </span>
            <span
              className={
                status === 'Conectado'
                  ? 'text-emerald-400'
                  : status === 'Conectando...' || status === 'Reconectando'
                  ? 'text-amber-400'
                  : status === 'Error'
                  ? 'text-red-400'
                  : 'text-slate-400'
              }
            >
              {statusDetail}
            </span>
          </div>
        </div>

        {/* Advertencia de entorno real */}
        <div className="p-3 rounded-xl bg-slate-950 border border-slate-800/80 text-[11px] text-slate-400 flex items-start gap-2.5">
          <ShieldAlert className="w-4 h-4 text-amber-400 shrink-0 mt-0.5" />
          <span>
            <strong>Aviso de entorno:</strong> La conexión VPN real solo funciona en el <strong>APK instalado</strong> en Android (utiliza <code className="text-slate-200">VpnService</code> y <code className="text-slate-200">tun2socks</code>). Jamás se fingirá un estado "Conectado" en esta vista web.
          </span>
        </div>

        {/* Selector de modo: BHTTP (v2) | WS (Custom) */}
        <div>
          <label className="block text-xs font-bold text-slate-400 uppercase tracking-wider mb-1.5">
            Modo de Conexión
          </label>
          <div className="grid grid-cols-2 gap-2 bg-slate-950 p-1 rounded-xl border border-slate-800">
            <button
              type="button"
              onClick={() => {
                setMode('BHTTP');
                setForm(prev => ({ ...prev, mode: 'BHTTP' }));
                setErrors(prev => ({ ...prev, payload: undefined }));
              }}
              className={`py-2 rounded-lg text-xs font-bold transition-all ${
                mode === 'BHTTP'
                  ? 'bg-[#0A5CFF] text-white shadow'
                  : 'text-slate-400 hover:text-white'
              }`}
            >
              BHTTP (v2)
            </button>
            <button
              type="button"
              onClick={() => {
                setMode('WS');
                setForm(prev => ({ ...prev, mode: 'WS' }));
              }}
              className={`py-2 rounded-lg text-xs font-bold transition-all ${
                mode === 'WS'
                  ? 'bg-[#0A5CFF] text-white shadow'
                  : 'text-slate-400 hover:text-white'
              }`}
            >
              WS (Custom)
            </button>
          </div>
        </div>

        {/* Formulario */}
        <div className="space-y-3 text-xs">
          
          {/* Servidor */}
          <div>
            <label className="block font-semibold text-slate-400 mb-1">
              Servidor (IP o dominio)
            </label>
            <input
              type="text"
              value={form.host}
              onChange={e => {
                setForm({ ...form, host: e.target.value });
                if (errors.host) setErrors({ ...errors, host: undefined });
              }}
              placeholder="IP o dominio del servidor"
              className={`w-full bg-[#1E293B] border rounded-lg px-3 py-2 text-white placeholder-slate-500 outline-none text-xs font-mono transition-colors ${
                errors.host ? 'border-red-500' : 'border-slate-800 focus:border-[#0A5CFF]'
              }`}
            />
            {errors.host && (
              <p className="text-red-400 text-[11px] mt-1 font-sans">{errors.host}</p>
            )}
          </div>

          {/* Puerto */}
          <div>
            <label className="block font-semibold text-slate-400 mb-1">
              Puerto {mode === 'BHTTP' ? '(BHTTP=443)' : '(WS=80)'}
            </label>
            <input
              type="number"
              value={form.port}
              onChange={e => {
                setForm({ ...form, port: e.target.value });
                if (errors.port) setErrors({ ...errors, port: undefined });
              }}
              placeholder={mode === 'BHTTP' ? '443' : '80'}
              className={`w-full bg-[#1E293B] border rounded-lg px-3 py-2 text-white placeholder-slate-500 outline-none text-xs font-mono transition-colors ${
                errors.port ? 'border-red-500' : 'border-slate-800 focus:border-[#0A5CFF]'
              }`}
            />
            {errors.port && (
              <p className="text-red-400 text-[11px] mt-1 font-sans">{errors.port}</p>
            )}
          </div>

          {/* Usuario SSH */}
          <div>
            <label className="block font-semibold text-slate-400 mb-1">
              Usuario SSH
            </label>
            <input
              type="text"
              value={form.user}
              onChange={e => {
                setForm({ ...form, user: e.target.value });
                if (errors.user) setErrors({ ...errors, user: undefined });
              }}
              placeholder="Usuario del servidor SSH"
              className={`w-full bg-[#1E293B] border rounded-lg px-3 py-2 text-white placeholder-slate-500 outline-none text-xs font-mono transition-colors ${
                errors.user ? 'border-red-500' : 'border-slate-800 focus:border-[#0A5CFF]'
              }`}
            />
            {errors.user && (
              <p className="text-red-400 text-[11px] mt-1 font-sans">{errors.user}</p>
            )}
          </div>

          {/* Contraseña SSH */}
          <div>
            <label className="block font-semibold text-slate-400 mb-1">
              Contraseña SSH
            </label>
            <div className="relative">
              <input
                type={showPass ? 'text' : 'password'}
                value={form.pass}
                onChange={e => {
                  setForm({ ...form, pass: e.target.value });
                  if (errors.pass) setErrors({ ...errors, pass: undefined });
                }}
                placeholder="Contraseña del usuario SSH"
                className={`w-full bg-[#1E293B] border rounded-lg pl-3 pr-9 py-2 text-white placeholder-slate-500 outline-none text-xs font-mono transition-colors ${
                  errors.pass ? 'border-red-500' : 'border-slate-800 focus:border-[#0A5CFF]'
                }`}
              />
              <button
                type="button"
                onClick={() => setShowPass(!showPass)}
                className="absolute right-2.5 top-1/2 -translate-y-1/2 text-slate-400 hover:text-white"
              >
                {showPass ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
              </button>
            </div>
            {errors.pass && (
              <p className="text-red-400 text-[11px] mt-1 font-sans">{errors.pass}</p>
            )}
          </div>

          {/* Campo Payload (Visible SOLO en modo WS) */}
          {mode === 'WS' && (
            <div>
              <label className="block font-semibold text-slate-400 mb-1">
                Payload (solo modo WS)
              </label>
              <textarea
                rows={3}
                value={form.payload}
                onChange={e => {
                  setForm({ ...form, payload: e.target.value });
                  if (errors.payload) setErrors({ ...errors, payload: undefined });
                }}
                placeholder="GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf][crlf]"
                className={`w-full bg-[#1E293B] border rounded-lg p-2.5 text-white placeholder-slate-500 outline-none text-xs font-mono transition-colors resize-none leading-relaxed ${
                  errors.payload ? 'border-red-500' : 'border-slate-800 focus:border-[#0A5CFF]'
                }`}
              />
              {errors.payload && (
                <p className="text-red-400 text-[11px] mt-1 font-sans">{errors.payload}</p>
              )}
            </div>
          )}

          {/* Botón CONECTAR / DESCONECTAR */}
          <button
            type="button"
            onClick={handleToggle}
            className={`w-full py-3 rounded-xl font-bold text-sm tracking-wide transition-all shadow-lg flex items-center justify-center cursor-pointer ${
              status === 'Conectado' || status === 'Conectando...' || status === 'Reconectando'
                ? 'bg-red-600 hover:bg-red-500 text-white'
                : 'bg-[#0A5CFF] hover:bg-blue-600 text-white'
            }`}
          >
            {status === 'Conectado' || status === 'Conectando...' || status === 'Reconectando'
              ? 'DESCONECTAR'
              : 'CONECTAR'}
          </button>

          {/* Registro real de eventos */}
          <div className="pt-2">
            <div className="flex items-center justify-between pb-1">
              <span className="text-[10px] font-bold text-slate-500 uppercase tracking-wider">
                Registro de Eventos
              </span>
              {logs.length > 0 && (
                <button
                  type="button"
                  onClick={clearLogs}
                  className="text-[10px] text-slate-500 hover:text-slate-300"
                >
                  Limpiar
                </button>
              )}
            </div>

            <div className="h-40 bg-[#020617] border border-slate-900 rounded-xl p-2.5 font-mono text-[11px] overflow-y-auto space-y-1 select-text">
              {logs.length === 0 ? (
                <span className="text-slate-600 italic">Listo para conectar.</span>
              ) : (
                logs.map((log, i) => (
                  <div key={i} className="text-slate-300 leading-relaxed break-words">
                    {log}
                  </div>
                ))
              )}
              <div ref={logEndRef} />
            </div>
          </div>

        </div>

      </div>
    </div>
  );
}
