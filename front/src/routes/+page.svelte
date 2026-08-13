<script lang="ts">
	import { prefersReducedMotion } from 'svelte/motion';
	import { fly } from 'svelte/transition';
	import logo from '$lib/assets/virter-logo.png';
	import { getDifficultyMask, mergeHandles } from '$lib/search';

	const divisions = [
		{ label: 'Div. 1', value: 32 },
		{ label: 'Div. 1 + Div. 2', value: 16 },
		{ label: 'Div. 2', value: 4 },
		{ label: 'Div. 2 Educational', value: 8 },
		{ label: 'Div. 3', value: 2 },
		{ label: 'Div. 4', value: 1 }
	] as const;

	let handles = $state<string[]>([]);
	let handleInput = $state('');
	let handleInputElement: HTMLInputElement;
	let selectedDivisions = $state(new Set<number>(divisions.map(({ value }) => value)));

	const difficultyMask = $derived(getDifficultyMask([...selectedDivisions]));

	function commitHandles() {
		handles = mergeHandles(handles, handleInput);
		handleInput = '';
		handleInputElement.value = '';
	}

	function updateHandleInput(event: Event) {
		handleInput = (event.currentTarget as HTMLInputElement).value;
		if (/\s/.test(handleInput)) commitHandles();
	}

	function handleKeydown(event: KeyboardEvent) {
		if (event.key === 'Enter') {
			event.preventDefault();
			commitHandles();
			return;
		}

		if (event.key === 'Backspace' && handleInput === '') {
			handles = handles.slice(0, -1);
		}
	}

	function focusHandleInput(event: PointerEvent) {
		if (!(event.target as HTMLElement).closest('button')) handleInputElement.focus();
	}

	function removeHandle(index: number) {
		handles = handles.filter((_, handleIndex) => handleIndex !== index);
	}

	function toggleDivision(value: number) {
		const next = new Set(selectedDivisions);
		if (next.has(value)) next.delete(value);
		else next.add(value);
		selectedDivisions = next;
	}
</script>

<svelte:head>
	<title>Virter | Codeforces Virtual Contest 추천</title>
	<meta
		name="description"
		content="여러 Codeforces 사용자가 아직 참여하지 않은 Virtual Contest를 찾아보세요."
	/>
</svelte:head>

<main>
	<div class="logo-frame">
		<img src={logo} alt="Virter" />
	</div>

	<section class="search" aria-label="대회 검색 조건">
		<div class="handle-input" role="group" aria-label="Codeforces 핸들 입력" onpointerdown={focusHandleInput}>
			{#each handles as handle, index}
				<span class="handle-token">
					<span>{handle}</span>
					<button
						type="button"
						aria-label={`${handle} 삭제`}
						onclick={() => removeHandle(index)}
					>×</button>
				</span>
			{/each}

			<label class="sr-only" for="handle">Codeforces 핸들</label>
			<input
				id="handle"
				bind:this={handleInputElement}
				value={handleInput}
				oninput={updateHandleInput}
				onkeydown={handleKeydown}
				onblur={commitHandles}
				placeholder={handles.length === 0 ? 'Codeforces 핸들을 입력하세요' : '핸들 추가'}
				autocomplete="off"
				spellcheck="false"
			/>
		</div>

		{#if handles.length > 0}
			<div
				class="search-options"
				transition:fly={{ y: 12, duration: prefersReducedMotion.current ? 0 : 220 }}
			>
				<fieldset>
					<legend class="sr-only">대회 유형</legend>
					<div class="division-grid">
						{#each divisions as division}
							<label class:checked={selectedDivisions.has(division.value)}>
								<input
									type="checkbox"
									checked={selectedDivisions.has(division.value)}
									onchange={() => toggleDivision(division.value)}
								/>
								<span>{division.label}</span>
							</label>
						{/each}
					</div>
				</fieldset>

				<button class="search-button" type="button" disabled={difficultyMask === 0}>대회 찾기</button>
			</div>
		{/if}
	</section>
</main>

<style>
	:global(*) {
		box-sizing: border-box;
	}

	:global(html) {
		font-family: Inter, Pretendard, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
		color: #202124;
		background: #fffaf0;
	}

	:global(body) {
		margin: 0;
		min-width: 320px;
		min-height: 100vh;
		background-color: #fffaf0;
		background-image: url('/eco-canvas-ivory.webp');
		background-position: center;
		background-size: cover;
		background-attachment: fixed;
	}

	:global(button),
	:global(input) {
		font: inherit;
	}

	main {
		display: flex;
		min-height: 100svh;
		width: min(900px, 100%);
		margin: 0 auto;
		padding: clamp(68px, 13vh, 132px) 16px 48px;
		align-items: center;
		flex-direction: column;
	}

	.logo-frame {
		display: flex;
		width: min(480px, 90vw);
		aspect-ratio: 16 / 9;
		margin-bottom: 28px;
		align-items: center;
	}

	.logo-frame img {
		display: block;
		width: 100%;
		height: 100%;
		object-fit: contain;
	}

	.search {
		width: 100%;
	}

	.handle-input {
		display: flex;
		min-height: 48px;
		width: 100%;
		padding: 6px 12px;
		align-items: center;
		flex-wrap: wrap;
		gap: 6px;
		border: 1px solid #c7c9cc;
		border-radius: 24px;
		background: #fff;
		box-shadow: 0 1px 2px rgb(60 64 67 / 8%);
		cursor: text;
		transition: border-color 140ms ease, box-shadow 140ms ease;
	}

	.handle-input:focus-within {
		border-color: #9aa0a6;
		box-shadow: 0 1px 6px rgb(60 64 67 / 22%);
	}

	.handle-input input {
		min-width: 150px;
		height: 34px;
		padding: 0 4px;
		flex: 1;
		border: 0;
		outline: 0;
		color: #202124;
		background: transparent;
	}

	.handle-input input::placeholder {
		color: #8a8f95;
	}

	.handle-token {
		display: inline-flex;
		max-width: 100%;
		height: 32px;
		padding-left: 12px;
		align-items: center;
		gap: 7px;
		border: 1px solid #dadce0;
		border-radius: 16px;
		font-size: 0.88rem;
		background: #f1f3f4;
	}

	.handle-token > span {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	.handle-token button {
		display: grid;
		width: 30px;
		height: 30px;
		padding: 0;
		place-items: center;
		border: 0;
		border-radius: 50%;
		font-size: 1.15rem;
		line-height: 1;
		color: #5f6368;
		background: transparent;
		cursor: pointer;
	}

	.handle-token button:hover {
		color: #202124;
		background: #e2e5e8;
	}

	.search-options {
		margin-top: 14px;
	}

	fieldset {
		margin: 0;
		padding: 5px;
		border: 1px solid #c7c9cc;
		border-radius: 24px;
		background: #fff;
	}

	.division-grid {
		display: grid;
		grid-template-columns: repeat(6, minmax(0, 1fr));
		gap: 4px;
	}

	.division-grid label {
		display: flex;
		min-width: 0;
		height: 38px;
		padding: 0 8px;
		align-items: center;
		justify-content: center;
		border: 1px solid #dadce0;
		border-radius: 19px;
		font-size: 0.78rem;
		font-weight: 600;
		text-align: center;
		color: #5f6368;
		cursor: pointer;
		transition: color 120ms ease, background 120ms ease;
	}

	.division-grid label:hover {
		background: #f1f3f4;
	}

	.division-grid label.checked {
		border-color: #aecbfa;
		color: #174ea6;
		background: #e8f0fe;
	}

	.division-grid input {
		position: absolute;
		width: 1px;
		height: 1px;
		overflow: hidden;
		clip: rect(0, 0, 0, 0);
		white-space: nowrap;
		clip-path: inset(50%);
	}

	.division-grid label:has(input:focus-visible) {
		outline: 2px solid #1a73e8;
		outline-offset: 2px;
	}

	.search-button {
		width: 100%;
		height: 44px;
		margin-top: 10px;
		border: 0;
		border-radius: 22px;
		font-weight: 650;
		color: #fff;
		background: #303134;
		cursor: pointer;
		transition: background 120ms ease, opacity 120ms ease;
	}

	.search-button:hover:not(:disabled) {
		background: #202124;
	}

	.search-button:disabled {
		opacity: 0.38;
		cursor: not-allowed;
	}

	.handle-token button:focus-visible,
	.search-button:focus-visible {
		outline: 2px solid #1a73e8;
		outline-offset: 2px;
	}

	.sr-only {
		position: absolute;
		width: 1px;
		height: 1px;
		padding: 0;
		overflow: hidden;
		clip: rect(0, 0, 0, 0);
		white-space: nowrap;
		border: 0;
	}

	@media (max-width: 720px) {
		main {
			padding-top: 64px;
		}

		.division-grid {
			grid-template-columns: repeat(3, minmax(0, 1fr));
		}
	}

	@media (max-width: 460px) {
		.division-grid {
			grid-template-columns: repeat(2, minmax(0, 1fr));
		}
	}

	@media (prefers-reduced-motion: reduce) {
		*,
		*::before,
		*::after {
			transition: none !important;
		}
	}
</style>
