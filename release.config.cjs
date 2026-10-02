module.exports = {
    branches: ['main'],

    tagFormat: 'v${version}',

    plugins: [
        [
            '@semantic-release/commit-analyzer',
            {
                preset: 'conventionalcommits',
                releaseRules: [
                    { type: 'feat', release: 'minor' },
                    { type: 'fix', release: 'patch' }
                ]
            }
        ],

        [
            '@semantic-release/release-notes-generator',
            {
                preset: 'conventionalcommits'
            }
        ],

        '@semantic-release/github'
    ]
};
