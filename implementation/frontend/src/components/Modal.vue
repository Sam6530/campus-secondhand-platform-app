<script setup>
import {ref,onMounted,onUnmounted} from 'vue';
const emit=defineEmits(['close']);const root=ref();const previous=document.activeElement;
function keys(e){if(e.key==='Escape'){e.preventDefault();emit('close');}if(e.key==='Tab'){const els=[...root.value.querySelectorAll('button,input,textarea,select,a[href]')].filter(e=>!e.disabled);const first=els[0],last=els.at(-1);if(e.shiftKey&&document.activeElement===first){e.preventDefault();last?.focus();}else if(!e.shiftKey&&document.activeElement===last){e.preventDefault();first?.focus();}}}
onMounted(()=>{root.value.querySelector('input,button,textarea')?.focus();document.addEventListener('keydown',keys);});onUnmounted(()=>{document.removeEventListener('keydown',keys);previous?.focus();});
defineProps({title:String});
</script>
<template><div class="backdrop" @click.self="emit('close')"><section ref="root" class="modal" role="dialog" aria-modal="true" aria-labelledby="modal-title"><div class="row"><h2 id="modal-title">{{ title }}</h2><button aria-label="关闭弹窗" @click="emit('close')">关闭</button></div><slot/></section></div></template>
