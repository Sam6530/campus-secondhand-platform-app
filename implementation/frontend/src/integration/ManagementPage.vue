<script setup>
import { ref, onMounted } from 'vue';
const props=defineProps(['user','client']);
const emit=defineEmits(['changed']);
const data=ref({}), reasons=ref({}), busy=ref(false), error=ref(''), result=ref('');
async function load(){data.value=await props.client.request('/admin/dashboard')}
onMounted(()=>load().catch(e=>error.value=e.message));
async function review(kind,row,pass){
 const reason=(reasons.value[row.id]||'').trim();
 if(!reason||[...reason].length>1500){error.value='审核依据需为1至1500字';return}
 busy.value=true; error.value='';result.value='';
 try{await props.client.request('/admin/'+kind+'/'+row.id,{method:'POST',body:{version:row.version,pass,reason}});result.value='审核结果已保存。';try{await load()}catch(e){error.value='审核已保存，列表刷新失败，请刷新查看，勿重复审核。'}emit('changed')}
 catch(e){error.value=e.message}finally{busy.value=false}
}
</script>
<template><section><h1>待审核事项</h1><p v-if="result" role="status">{{result}}</p><p v-if="error" role="alert">{{error}}</p>
<section v-for="[kind,label,rows] in [['verification','身份认证',data.verifications],['item','物品发布',data.items]]" :key="kind">
<h2>{{label}}</h2><p v-if="rows && !rows.length">暂无待审核记录</p>
<article v-for="row in rows" :key="row.id" class="card">
<h3>{{row.title || row.real_name}}</h3><p>{{row.student_no || row.description}} · 版本 {{row.version}}</p>
<img v-if="row.asset_id || row.image_id" :src="'/api/assets/'+(row.asset_id || row.image_id)" alt="待审核材料" style="max-width:100%;max-height:20rem">
<label>审核依据<input v-model="reasons[row.id]" maxlength="3000"></label>
<button :disabled="busy" @click="review(kind,row,true)">通过</button><button :disabled="busy" @click="review(kind,row,false)">退回</button>
</article></section></section></template>
