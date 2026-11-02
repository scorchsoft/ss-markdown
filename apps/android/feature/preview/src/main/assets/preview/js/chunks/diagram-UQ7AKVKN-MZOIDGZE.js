import{a as F}from"./chunk-CPEZORP3.js";import{a as D}from"./chunk-VC5G2C7V.js";import"./chunk-GEUIRHFL.js";import"./chunk-JLKCWBL4.js";import{a as E}from"./chunk-FQL4IP74.js";import"./chunk-MFBLEF5E.js";import"./chunk-H5HOEII2.js";import"./chunk-NDJSLTNG.js";import"./chunk-2FNS6SG3.js";import"./chunk-QWD4ZDOX.js";import"./chunk-UBGXEFXL.js";import"./chunk-ERRU54G3.js";import"./chunk-LQWVVYSS.js";import"./chunk-6EPRQ5GD.js";import"./chunk-QBETNKEI.js";import"./chunk-7W4YWXM4.js";import"./chunk-F4VE5EGU.js";import"./chunk-IWXKBZOM.js";import"./chunk-DYO7FNYG.js";import{o as y}from"./chunk-CEYDWV42.js";import"./chunk-XG3BLBRS.js";import{N as L,S as T,T as S,U as k,V as O,W as R,X as I,Y as _,h as A,j as M,t as b}from"./chunk-7SGX4G3K.js";import"./chunk-FTY6WLQF.js";import{b as C}from"./chunk-FGCK5FFD.js";import{a as c}from"./chunk-EIBFAW37.js";import"./chunk-7SZRL6FY.js";var x={showLegend:!0,ticks:5,max:null,min:0,graticule:"circle"},w=32,P={axes:[],curves:[],options:x},g=structuredClone(P),U=M.radar,X=c(()=>y({...U,...b().radar}),"getConfig"),z=c(()=>g.axes,"getAxes"),K=c(()=>g.curves,"getCurves"),N=c(()=>g.options,"getOptions"),Y=c(a=>{g.axes=a.map(t=>({name:t.name,label:t.label??t.name}))},"setAxes"),Z=c(a=>{g.curves=a.map(t=>({name:t.name,label:t.label??t.name,entries:q(t.entries)}))},"setCurves"),q=c(a=>{if(a[0].axis==null)return a.map(e=>e.value);let t=z();if(t.length===0)throw new Error("Axes must be populated before curves for reference entries");return t.map(e=>{let r=a.find(n=>{var s;return((s=n.axis)==null?void 0:s.$refText)===e.name});if(r===void 0)throw new Error("Missing entry for axis "+e.label);return r.value})},"computeCurveEntries"),J=c(a=>{var e,r,n,s,l;let t=a.reduce((o,i)=>(o[i.name]=i,o),{});g.options={showLegend:((e=t.showLegend)==null?void 0:e.value)??x.showLegend,ticks:((r=t.ticks)==null?void 0:r.value)??x.ticks,max:((n=t.max)==null?void 0:n.value)??x.max,min:((s=t.min)==null?void 0:s.value)??x.min,graticule:((l=t.graticule)==null?void 0:l.value)??x.graticule},g.options.ticks>w&&(C.warn(`Radar diagram ticks (${g.options.ticks}) exceeds maximum allowed (${w}). Using ${w} instead.`),g.options.ticks=w)},"setOptions"),Q=c(()=>{T(),g=structuredClone(P)},"clear"),$={getAxes:z,getCurves:K,getOptions:N,setAxes:Y,setCurves:Z,setOptions:J,getConfig:X,clear:Q,setAccTitle:S,getAccTitle:k,setDiagramTitle:I,getDiagramTitle:_,getAccDescription:R,setAccDescription:O},tt=c(a=>{F(a,$);let{axes:t,curves:e,options:r}=a;$.setAxes(t),$.setCurves(e),$.setOptions(r)},"populate"),et={parse:c(async a=>{let t=await D("radar",a);C.debug(t),tt(t)},"parse")},at=c((a,t,e,r)=>{let n=r.db,s=n.getAxes(),l=n.getCurves(),o=n.getOptions(),i=n.getConfig(),d=n.getDiagramTitle(),p=E(t),u=rt(p,i),m=o.max??Math.max(...l.map(f=>Math.max(...f.entries))),h=o.min,v=Math.min(i.width,i.height)/2;nt(u,s,v,o.ticks,o.graticule),st(u,s,v,i),G(u,s,l,h,m,o.graticule,i),V(u,l,o.showLegend,i),u.append("text").attr("class","radarTitle").text(d).attr("x",0).attr("y",-i.height/2-i.marginTop)},"draw"),rt=c((a,t)=>{let e=t.width+t.marginLeft+t.marginRight,r=t.height+t.marginTop+t.marginBottom,n={x:t.marginLeft+t.width/2,y:t.marginTop+t.height/2};return L(a,r,e,t.useMaxWidth??!0),a.attr("viewBox",`0 0 ${e} ${r}`).attr("overflow","visible"),a.append("g").attr("transform",`translate(${n.x}, ${n.y})`)},"drawFrame"),nt=c((a,t,e,r,n)=>{if(n==="circle")for(let s=0;s<r;s++){let l=e*(s+1)/r;a.append("circle").attr("r",l).attr("class","radarGraticule")}else if(n==="polygon"){let s=t.length;for(let l=0;l<r;l++){let o=e*(l+1)/r,i=t.map((d,p)=>{let u=2*p*Math.PI/s-Math.PI/2,m=o*Math.cos(u),h=o*Math.sin(u);return`${m},${h}`}).join(" ");a.append("polygon").attr("points",i).attr("class","radarGraticule")}}},"drawGraticule"),st=c((a,t,e,r)=>{let n=t.length;for(let s=0;s<n;s++){let l=t[s].label,o=2*s*Math.PI/n-Math.PI/2,i=Math.cos(o),d=Math.sin(o);a.append("line").attr("x1",0).attr("y1",0).attr("x2",e*r.axisScaleFactor*i).attr("y2",e*r.axisScaleFactor*d).attr("class","radarAxisLine");let p=i>.01?"start":i<-.01?"end":"middle",u=d>.01?"hanging":d<-.01?"auto":"central",m=4;a.append("text").text(l).attr("x",e*r.axisLabelFactor*i+m*i).attr("y",e*r.axisLabelFactor*d+m*d).attr("text-anchor",p).attr("dominant-baseline",u).attr("class","radarAxisLabel")}},"drawAxes");function G(a,t,e,r,n,s,l){let o=t.length,i=Math.min(l.width,l.height)/2;e.forEach((d,p)=>{if(d.entries.length!==o)return;let u=d.entries.map((m,h)=>{let v=2*Math.PI*h/o-Math.PI/2,f=B(m,r,n,i),H=f*Math.cos(v),j=f*Math.sin(v);return{x:H,y:j}});s==="circle"?a.append("path").attr("d",W(u,l.curveTension)).attr("class",`radarCurve-${p}`):s==="polygon"&&a.append("polygon").attr("points",u.map(m=>`${m.x},${m.y}`).join(" ")).attr("class",`radarCurve-${p}`)})}c(G,"drawCurves");function B(a,t,e,r){let n=Math.min(Math.max(a,t),e);return r*(n-t)/(e-t)}c(B,"relativeRadius");function W(a,t){let e=a.length,r=`M${a[0].x},${a[0].y}`;for(let n=0;n<e;n++){let s=a[(n-1+e)%e],l=a[n],o=a[(n+1)%e],i=a[(n+2)%e],d={x:l.x+(o.x-s.x)*t,y:l.y+(o.y-s.y)*t},p={x:o.x-(i.x-l.x)*t,y:o.y-(i.y-l.y)*t};r+=` C${d.x},${d.y} ${p.x},${p.y} ${o.x},${o.y}`}return`${r} Z`}c(W,"closedRoundCurve");function V(a,t,e,r){if(!e)return;let n=(r.width/2+r.marginRight)*3/4,s=-(r.height/2+r.marginTop)*3/4,l=20;t.forEach((o,i)=>{let d=a.append("g").attr("transform",`translate(${n}, ${s+i*l})`);d.append("rect").attr("width",12).attr("height",12).attr("class",`radarLegendBox-${i}`),d.append("text").attr("x",16).attr("y",0).attr("class","radarLegendText").text(o.label)})}c(V,"drawLegend");var ot={draw:at},it=c((a,t)=>{let e="";for(let r=0;r<a.THEME_COLOR_LIMIT;r++){let n=a[`cScale${r}`];e+=`
		.radarCurve-${r} {
			color: ${n};
			fill: ${n};
			fill-opacity: ${t.curveOpacity};
			stroke: ${n};
			stroke-width: ${t.curveStrokeWidth};
		}
		.radarLegendBox-${r} {
			fill: ${n};
			fill-opacity: ${t.curveOpacity};
			stroke: ${n};
		}
		`}return e},"genIndexStyles"),lt=c(a=>{let t=A(),e=b(),r=y(t,e.themeVariables),n=y(r.radar,a);return{themeVariables:r,radarOptions:n}},"buildRadarStyleOptions"),ct=c(({radar:a}={})=>{let{themeVariables:t,radarOptions:e}=lt(a);return`
	.radarTitle {
		font-size: ${t.fontSize};
		color: ${t.titleColor};
		dominant-baseline: hanging;
		text-anchor: middle;
	}
	.radarAxisLine {
		stroke: ${e.axisColor};
		stroke-width: ${e.axisStrokeWidth};
	}
	.radarAxisLabel {
		font-size: ${e.axisLabelFontSize}px;
		color: ${e.axisColor};
	}
	.radarGraticule {
		fill: ${e.graticuleColor};
		fill-opacity: ${e.graticuleOpacity};
		stroke: ${e.graticuleColor};
		stroke-width: ${e.graticuleStrokeWidth};
	}
	.radarLegendText {
		text-anchor: start;
		font-size: ${e.legendFontSize}px;
		dominant-baseline: hanging;
	}
	${it(t,e)}
	`},"styles"),vt={parser:et,db:$,renderer:ot,styles:ct};export{vt as diagram};
