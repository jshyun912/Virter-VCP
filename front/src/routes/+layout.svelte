<script lang="ts">
	import favicon from '$lib/assets/virter-logo.png';

	let { children } = $props();
</script>

<svelte:head>
	<link rel="icon" href={favicon} />
</svelte:head>

{@render children()}
