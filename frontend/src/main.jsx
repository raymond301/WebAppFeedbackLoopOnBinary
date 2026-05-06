import React, { useEffect, useMemo, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { ScatterChart, Scatter, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts';

const API = import.meta.env.VITE_API_BASE || 'http://localhost:8080';

function App() {
  const [tree, setTree] = useState({});
  const [sel, setSel] = useState({ image: '', marker: '', packet: '' });
  const [manifest, setManifest] = useState(null);
  const [threshold, setThreshold] = useState(0);
  const [userEmail, setUserEmail] = useState('');

  useEffect(() => { fetch(`${API}/api/tree`).then(r=>r.json()).then(d=>setTree(d.images||{})); }, []);
  useEffect(() => {
    if (!sel.image || !sel.marker || !sel.packet) return;
    fetch(`${API}/api/packet/${sel.image}/${sel.marker}/${sel.packet}/manifest`).then(r=>r.json()).then(d=>{setManifest(d);setThreshold(d.snr_min);});
  }, [sel]);

  const classified = useMemo(()=> (manifest?.points||[]).map(p=>({...p, Local_X:p.X, Local_Y:p.Y, dynClass: p.SNR>=threshold?'+':'-'})), [manifest, threshold]);

  const save = async (status)=> {
    await fetch(`${API}/api/packet/${sel.image}/${sel.marker}/${sel.packet}/decision`, {method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({user_email:userEmail,status,threshold})});
    alert('Saved');
  }

  return <div style={{display:'flex',fontFamily:'sans-serif'}}>
    <div style={{width:320,padding:12,borderRight:'1px solid #ddd',height:'100vh',overflow:'auto'}}>
      <h3>Manifest Browser</h3>
      {Object.entries(tree).map(([img, markers])=><div key={img}><b>{img}</b>
        {Object.entries(markers).map(([m, packets])=><div key={m} style={{marginLeft:12}}>
          <div>{m}</div>
          {packets.map(p=><button key={p.packet} onClick={()=>setSel({image:img,marker:m,packet:p.packet})} style={{display:'block'}}>{p.packet} (+){p.plus_count}</button>)}
        </div>)}
      </div>)}
    </div>
    <div style={{flex:1,padding:12}}>
      <h3>Validation Workspace</h3>
      {manifest && <>
        <div>SNR Range {manifest.snr_min} - {manifest.snr_max}</div>
        <input type='range' min={manifest.snr_min} max={manifest.snr_max} step='0.01' value={threshold} onChange={e=>setThreshold(Number(e.target.value))} style={{width:'100%'}}/>
        <div>Threshold: {threshold.toFixed(2)}</div>
        <div style={{height:400}}>
          <ResponsiveContainer><ScatterChart><XAxis dataKey='Local_X' /><YAxis dataKey='Local_Y' /><Tooltip />
            <Scatter data={classified.filter(x=>x.dynClass==='+')} fill='green'/>
            <Scatter data={classified.filter(x=>x.dynClass==='-')} fill='red'/>
          </ScatterChart></ResponsiveContainer>
        </div>
        <input placeholder='user email' value={userEmail} onChange={e=>setUserEmail(e.target.value)} />
        <div><button onClick={()=>save('confirm_threshold')}>Confirm Threshold</button><button onClick={()=>save('adjusted')}>Adjusted</button><button onClick={()=>save('reject_packet')}>Reject Packet</button></div>
      </>}
    </div>
  </div>
}

createRoot(document.getElementById('root')).render(<App />)
