package dev.alastorkaneki.vizzywrapper;

import org.json.JSONObject;

final class WebCompat {
    private WebCompat() {}

    static String script(int viewport, String platform, String platformName) {
        return "window.__VIZZY_WRAPPER_CONFIG={viewport:" + viewport
                + ",platform:" + JSONObject.quote(platform)
                + ",platformName:" + JSONObject.quote(platformName) + "};" + SCRIPT;
    }

    private static final String SCRIPT = """
            (function(){
              try {
                const cfg = window.__VIZZY_WRAPPER_CONFIG || {viewport:1280,platform:'Win32',platformName:'Windows'};
                document.documentElement.style.backgroundColor = '#000';
                if (document.body) document.body.style.backgroundColor = '#000';

                try { Object.defineProperty(navigator,'platform',{get:()=>cfg.platform,configurable:true}); } catch(e) {}
                try {
                  Object.defineProperty(navigator,'userAgentData',{configurable:true,get:()=>({
                    brands:[{brand:'Chromium',version:'150'},{brand:'Google Chrome',version:'150'},{brand:'Not_A Brand',version:'99'}],
                    mobile:cfg.platformName==='Android', platform:cfg.platformName,
                    getHighEntropyValues:async()=>({architecture:cfg.platformName==='Android'?'arm':'x86',bitness:'64',mobile:cfg.platformName==='Android',model:'',platform:cfg.platformName,platformVersion:'10.0.0',uaFullVersion:'150.0.0.0',fullVersionList:[{brand:'Chromium',version:'150.0.0.0'},{brand:'Google Chrome',version:'150.0.0.0'}]}),
                    toJSON:function(){return {brands:this.brands,mobile:this.mobile,platform:this.platform};}
                  })});
                } catch(e) {}

                if (cfg.viewport > 0) {
                  let meta = document.querySelector('meta[name="viewport"]');
                  if (!meta) { meta=document.createElement('meta'); meta.name='viewport'; (document.head||document.documentElement).appendChild(meta); }
                  const sw=Math.max(320,(window.screen&&window.screen.width)||360);
                  const scale=Math.max(.2,Math.min(1,sw/cfg.viewport));
                  meta.content='width='+cfg.viewport+', initial-scale='+scale+', minimum-scale=.2, maximum-scale=5, user-scalable=yes';
                }

                if (window.__vizzyNativeSaveInstalled) return;
                window.__vizzyNativeSaveInstalled = true;
                const jobs = Object.create(null);
                let sequence = 0;

                function safeName(name,fallback){
                  return String(name||fallback||'vizzy-export.webm').replace(/[\\/:*?\"<>|]/g,'_').trim() || 'vizzy-export.webm';
                }
                function bytesToBase64(bytes){
                  let binary=''; const block=0x8000;
                  for(let i=0;i<bytes.length;i+=block) binary+=String.fromCharCode.apply(null,bytes.subarray(i,Math.min(i+block,bytes.length)));
                  return btoa(binary);
                }
                async function dataToBytes(data){
                  if(data==null) return new Uint8Array(0);
                  if(typeof data==='string') return new TextEncoder().encode(data);
                  if(data instanceof Uint8Array) return data;
                  if(data instanceof ArrayBuffer) return new Uint8Array(data);
                  if(ArrayBuffer.isView(data)) return new Uint8Array(data.buffer,data.byteOffset,data.byteLength);
                  if(data instanceof Blob) return new Uint8Array(await data.arrayBuffer());
                  return new Uint8Array(await new Blob([data]).arrayBuffer());
                }
                async function sendBytes(id,bytes){
                  const size=256*1024;
                  for(let offset=0;offset<bytes.length;offset+=size){
                    const chunk=bytes.subarray(offset,Math.min(offset+size,bytes.length));
                    if(!AndroidSave.write(id,bytesToBase64(chunk))) throw new Error('Native file write failed');
                    await new Promise(resolve=>setTimeout(resolve,0));
                  }
                }
                function writable(id){
                  let closed=false;
                  return {
                    write:async function(data){
                      if(closed) throw new DOMException('Stream is closed','InvalidStateError');
                      if(data&&typeof data==='object'&&data.type){
                        if(data.type==='write') data=data.data;
                        else if(data.type==='seek'||data.type==='truncate') return;
                      }
                      await sendBytes(id,await dataToBytes(data));
                    },
                    close:async function(){if(!closed){closed=true;if(!AndroidSave.close(id))throw new Error('Could not close file');}},
                    abort:async function(reason){if(!closed){closed=true;AndroidSave.abort(id,String(reason||'Aborted'));}}
                  };
                }

                window.__vizzySavePicked=function(id,accepted,resolvedName){
                  const job=jobs[id]; if(!job)return;
                  if(!accepted){delete jobs[id];job.reject(new DOMException('The user aborted the request.','AbortError'));return;}
                  if(job.kind==='blob'){
                    (async()=>{
                      try{await sendBytes(id,new Uint8Array(await job.blob.arrayBuffer()));AndroidSave.close(id);}
                      catch(error){AndroidSave.abort(id,String(error));}
                      delete jobs[id];
                    })();
                  }else{
                    delete jobs[id];
                    job.resolve({kind:'file',name:resolvedName||job.name,queryPermission:async()=>'granted',requestPermission:async()=>'granted',createWritable:async()=>writable(id)});
                  }
                };

                window.__vizzySaveBlob=function(blob,name,mime){
                  const id='blob_'+Date.now()+'_'+(++sequence);
                  jobs[id]={kind:'blob',blob:blob,name:safeName(name,'vizzy-export.webm'),reject:()=>{}};
                  AndroidSave.pick(id,jobs[id].name,mime||blob.type||'application/octet-stream');
                };
                window.__vizzyOfferBlobUrl=async function(url,name,mime){
                  try{const response=await fetch(url);const blob=await response.blob();window.__vizzySaveBlob(blob,name,mime||blob.type);}
                  catch(error){console.error(error);}
                };

                document.addEventListener('click',function(event){
                  const anchor=event.target&&event.target.closest?event.target.closest('a'):null;
                  if(anchor&&anchor.href&&anchor.href.startsWith('blob:')){
                    event.preventDefault();event.stopImmediatePropagation();
                    window.__vizzyOfferBlobUrl(anchor.href,anchor.download||'vizzy-export.webm',anchor.type||'');
                  }
                },true);
                const originalOpen=window.open;
                window.open=function(url){
                  if(typeof url==='string'&&url.startsWith('blob:')){window.__vizzyOfferBlobUrl(url,'vizzy-export.webm','video/webm');return null;}
                  return originalOpen?originalOpen.apply(window,arguments):null;
                };

                if(typeof window.showSaveFilePicker!=='function'){
                  window.showSaveFilePicker=function(options){
                    options=options||{};const id='fs_'+Date.now()+'_'+(++sequence);const name=safeName(options.suggestedName,'vizzy-export.webm');
                    let mime='application/octet-stream';
                    try{const accept=options.types&&options.types[0]&&options.types[0].accept;if(accept)mime=Object.keys(accept)[0]||mime;}catch(e){}
                    return new Promise((resolve,reject)=>{jobs[id]={kind:'handle',name:name,resolve:resolve,reject:reject};AndroidSave.pick(id,name,mime);});
                  };
                }
              } catch(error) { try{console.error('Vizzy wrapper injection failed',error);}catch(e){} }
            })();
            """;
}
